package kr.co.cudo.authoring.video.service;

import kr.co.cudo.authoring.batch.repository.LsDataMetaRepository;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.video.service.port.VideoProbe.VideoMeta;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 영상 기술메타(ffprobe) 를 {@code LS_DATA_META} 에 {@code video.*} 키로 적재하는 서비스 —
 * NIA export Phase 2 (RQ-SFR-07-02 연계).
 *
 * <p>{@link VideoProbe} 결과를 아래 키로 매핑해 멱등 upsert 한다. (RAW_SN, META_KEY) UK 하에서
 * PostgreSQL {@code ON CONFLICT} 원자적 upsert({@link LsDataMetaRepository#upsertMeta})로 처리하므로
 * 동시 실행 race(CWE-362) 에서도 중복 행·값 유실 없이 값만 갱신한다.
 * null(미상) 필드는 저장하지 않는다 — "값 0"과 "미상"을 구분한다.
 *
 * <ul>
 *   <li>{@code video.fps} — 초당 프레임 수(양수만, 미상 skip)</li>
 *   <li>{@code video.codec} — 코덱명(미상/blank skip)</li>
 *   <li>{@code video.bit_rate} — 비트레이트(bps)</li>
 *   <li>{@code video.duration_ms} — 길이(ms)</li>
 *   <li>{@code video.filesize} — 파일 크기(byte)</li>
 *   <li>{@code video.resolution} — "WIDTHxHEIGHT" (width·height 둘 다 &gt;0 일 때만)</li>
 * </ul>
 *
 * <p>ffprobe 호출(blocking I/O)은 트랜잭션 밖에서 수행되므로 본 서비스는 이미 추출된
 * {@link VideoMeta} 만 받아 DB 쓰기만 담당한다({@link Propagation#REQUIRES_NEW} 독립 커밋).
 *
 * <p>추가로, probe 의 {@code duration_ms} 를 {@code LS_DATA_RAW.VDO_LEN_SEC}(초)로 <b>역류 back-fill</b>
 * 한다 — 주 적재 경로가 VDO_LEN_SEC 를 비운(NULL/0) 영상만 채운다({@link #upsertVideoMeta} 하단 참조).
 * probe 로 도출한 값들을 각자의 정본 컬럼(LS_DATA_META·LS_DATA_RAW)에 같은 커밋으로 적재한다.
 *
 * <p><b>동시 write 와의 lost-update 양방향 방어(CWE-362)</b>: back-fill 자체는 조건부 단일 컬럼 UPDATE
 * ({@link VideoRepository#backfillDurationSecIfBlank})라 동시 비식별 write 가 방금 커밋한 값을
 * <b>정방향</b>으로 되돌리지 않는다. <b>역방향</b>(다른 full-entity writer 가 stale {@code durationSec} 를 다시
 * 써 back-fill 을 무효화하는 경로)은 {@link kr.co.cudo.authoring.video.entity.LsDataRaw} 의
 * {@code @DynamicUpdate}(flush 시 실제 dirty 필드만 SET)가 시스템 차원에서 차단한다. 두 방어가 함께
 * back-fill 한 {@code VDO_LEN_SEC} 의 영속성을 완성한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VideoMetaService {

    /**
     * {@code LS_DATA_META.META_KEY} — video.* 기술메타 키 접두.
     *
     * <p>이 접두를 가진 키({@code video.fps/codec/bit_rate/duration_ms/filesize/resolution})는
     * <b>이 서비스가 증강 파일에서 ffprobe 로 소유</b>한다. 증강 신규 RAW 의 메타를 부모에서 복사할 때
     * ({@code AugmentFrameExtractionService}) 이 접두 키는 반드시 제외해야 한다 — 부모 파일 고유값
     * (파일크기·코덱·재생시간)을 다른 인코딩의 증강 파일 RAW 에 복사하면 논리 오손이고,
     * 메타러너 upsert 와 (RAW_SN, META_KEY) UNIQUE 충돌 레이스를 일으킨다(CWE-362).
     */
    public static final String KEY_PREFIX = "video.";

    /**
     * 해당 metaKey 가 {@code video.*} 기술메타(메타러너 소유)인지 판정한다. 증강 메타 복사 제외 필터에 사용.
     */
    public static boolean isTechnicalKey(String metaKey) {
        return metaKey != null && metaKey.startsWith(KEY_PREFIX);
    }

    /** {@code LS_DATA_META.META_KEY} — video.* 기술메타 키. */
    static final String KEY_FPS = "video.fps";
    static final String KEY_CODEC = "video.codec";
    static final String KEY_BIT_RATE = "video.bit_rate";
    static final String KEY_DURATION_MS = "video.duration_ms";
    static final String KEY_FILESIZE = "video.filesize";
    static final String KEY_RESOLUTION = "video.resolution";

    /** {@code LS_DATA_META.META_VL} 컬럼 길이(VARCHAR(2000)) — 외부 ffprobe 문자열 저장 전 길이 방어. */
    static final int META_VL_MAX = 2000;

    /** ffprobe {@code duration_ms} → {@code LS_DATA_RAW.VDO_LEN_SEC}(초) 환산 제수. */
    private static final double MILLIS_PER_SECOND = 1000.0;

    private final LsDataMetaRepository metaRepository;
    private final VideoRepository videoRepository;

    /**
     * probe 결과를 {@code video.*} 메타로 멱등 upsert 한다.
     *
     * <p>각 키를 {@link LsDataMetaRepository#upsertMeta} 원자 호출로 저장한다. (RAW_SN, META_KEY) UK
     * 충돌은 PostgreSQL {@code ON CONFLICT} 가 단일 문장 내에서 원자적으로 해소하므로 read-then-write
     * race(CWE-362) 가 존재하지 않는다 — 동시 스레드가 같은 키를 먼저 insert 해도 예외 없이 값이 갱신된다.
     * 상위 파이프라인 무중단(graceful) 격리는 {@code AsyncVideoMetaRunner} 가 담당한다.
     *
     * @param rawSn 대상 영상 PK (LS_DATA_RAW.RAW_SN)
     * @param meta  ffprobe 추출 결과. null 필드는 저장하지 않는다.
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public void upsertVideoMeta(Long rawSn, VideoMeta meta) {
        if (rawSn == null || meta == null) {
            return;
        }
        Map<String, String> desired = buildDesired(meta);
        if (desired.isEmpty()) {
            log.debug("[VideoMeta] no extractable field rawSn={} — skip", rawSn);
            return;
        }
        desired.forEach((key, value) -> metaRepository.upsertMeta(rawSn, key, value));
        log.info("[VideoMeta] stored rawSn={} keys={}", rawSn, desired.size());
        // 같은 REQUIRES_NEW 트랜잭션에서 VDO_LEN_SEC 를 함께 back-fill(단일 커밋, 아래 Javadoc 참조).
        backfillDurationSec(rawSn, meta.durationMs());
    }

    /**
     * ffprobe {@code duration_ms} 를 {@code LS_DATA_RAW.VDO_LEN_SEC}(초)로 역류 back-fill 한다.
     *
     * <p>주 적재 경로가 VDO_LEN_SEC 를 비운(NULL/0) 영상만 채운다 — "비어 있을 때만" 판정과 관제 유효값
     * 보존, 동시 비식별 write 가 이 back-fill 을 <b>정방향</b>으로 되돌리지 않는 것은 조건부 단일 컬럼 UPDATE
     * ({@link VideoRepository#backfillDurationSecIfBlank})의 WHERE 가 DB 에서 원자적으로 담당한다.
     * back-fill 한 값이 다른 full-entity writer 의 stale 스냅샷에 의해 <b>역방향</b>으로 무효화되는 경로는
     * {@link kr.co.cudo.authoring.video.entity.LsDataRaw} 의 {@code @DynamicUpdate} 가 차단한다(dirty 필드만 SET).
     * 본 write 는 상위 meta upsert 와 <b>동일한 REQUIRES_NEW 트랜잭션</b>에서 단일 커밋된다(probe 로 도출한
     * 값들을 각자의 정본 컬럼에 원자적으로 적재).
     *
     * <p>ffprobe 가 길이를 알려준 이상(durationMs&gt;0) 1초 미만이어도 최소 1초로 채운다(콘텐츠 존재 확정).
     * durationMs 가 null/≤0(미상)이면 back-fill 하지 않는다. row 부재는 영향 행수 0 으로 안전하게 skip 된다
     * (예외 미발생). 로그에는 rawSn·sec 만 남긴다(경로/PII 비노출 — CWE-209).
     *
     * @param durationMs ffprobe 길이(ms). null/≤0 이면 back-fill 하지 않는다.
     */
    private void backfillDurationSec(Long rawSn, Long durationMs) {
        if (durationMs == null || durationMs <= 0) {
            return;
        }
        int sec = (int) Math.max(1L, Math.round(durationMs / MILLIS_PER_SECOND));
        int affected = videoRepository.backfillDurationSecIfBlank(rawSn, sec);
        if (affected > 0) {
            log.info("[VideoMeta] VDO_LEN_SEC back-filled rawSn={} sec={}", rawSn, sec);
        }
    }

    /** null(미상)이 아닌 필드만 골라 (metaKey → metaVl) 로 구성한다. */
    private Map<String, String> buildDesired(VideoMeta meta) {
        Map<String, String> desired = new LinkedHashMap<>();
        if (meta.fps() != null) {
            desired.put(KEY_FPS, String.valueOf(meta.fps()));
        }
        if (StringUtils.hasText(meta.codecName())) {
            // 외부 ffprobe 자유양식 문자열 — META_VL(2000) 초과 시 방어적으로 절단(저장 실패/유실 예방).
            String codec = meta.codecName();
            if (codec.length() > META_VL_MAX) {
                log.warn("[VideoMeta] codec too long len={} — truncated to {}", codec.length(), META_VL_MAX);
                codec = codec.substring(0, META_VL_MAX);
            }
            desired.put(KEY_CODEC, codec);
        }
        if (meta.bitRate() != null) {
            desired.put(KEY_BIT_RATE, String.valueOf(meta.bitRate()));
        }
        if (meta.durationMs() != null) {
            desired.put(KEY_DURATION_MS, String.valueOf(meta.durationMs()));
        }
        if (meta.fileSize() != null) {
            desired.put(KEY_FILESIZE, String.valueOf(meta.fileSize()));
        }
        if (meta.width() > 0 && meta.height() > 0) {
            desired.put(KEY_RESOLUTION, meta.width() + "x" + meta.height());
        }
        return desired;
    }
}
