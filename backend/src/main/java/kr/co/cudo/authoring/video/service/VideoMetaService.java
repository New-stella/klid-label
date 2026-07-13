package kr.co.cudo.authoring.video.service;

import kr.co.cudo.authoring.batch.repository.LsDataMetaRepository;
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
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VideoMetaService {

    /** {@code LS_DATA_META.META_KEY} — video.* 기술메타 키. */
    static final String KEY_FPS = "video.fps";
    static final String KEY_CODEC = "video.codec";
    static final String KEY_BIT_RATE = "video.bit_rate";
    static final String KEY_DURATION_MS = "video.duration_ms";
    static final String KEY_FILESIZE = "video.filesize";
    static final String KEY_RESOLUTION = "video.resolution";

    /** {@code LS_DATA_META.META_VL} 컬럼 길이(VARCHAR(2000)) — 외부 ffprobe 문자열 저장 전 길이 방어. */
    static final int META_VL_MAX = 2000;

    private final LsDataMetaRepository metaRepository;

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
