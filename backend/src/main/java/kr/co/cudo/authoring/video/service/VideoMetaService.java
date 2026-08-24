package kr.co.cudo.authoring.video.service;

import kr.co.cudo.authoring.batch.repository.LsDataMetaRepository;
import kr.co.cudo.authoring.video.entity.LsDataIngest;
import kr.co.cudo.authoring.video.repository.LsDataIngestRepository;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.video.service.port.VideoProbe.VideoMeta;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 영상 기술메타를 {@code LS_DATA_META} 에 {@code video.*} 키로 적재하는 서비스 —
 * NIA export Phase 2 (RQ-SFR-07-02 연계).
 *
 * <h3>소스 우선순위 — 관제 인입값 우선, 없는 키만 ffprobe (설계 §6-2)</h3>
 * <p>관제가 {@code LS_DATA_INGEST} 에 같은 기술메타를 이미 넣어 주므로, 파일을 열어 재측정하는 대신
 * 그 값을 쓴다. 폴백은 <b>키 단위</b>다 — 인입이 3개만 채웠으면 그 3개는 인입값, 나머지만 ffprobe 다
 * ("하나라도 없으면 전부 ffprobe" 가 아니다). 인입 행이 아예 없는 <b>파생영상(증강·해상도 — 저작도구가
 * 만든다)</b>은 자연히 전량 ffprobe 로 채워진다.
 *
 * <p>{@code LS_DATA_META} 의 키-값(EAV) 구조와 키 이름 6종은 <b>바뀌지 않는다</b>. 소비처
 * ({@code VideoFpsResolver}·{@code VideoDurationResolver}·{@code VideoDurationDbReader}·
 * {@code DatasetMetaSourceRepository}·{@code DatasetVideoMetaSnapshotService})와
 * {@link #isTechnicalKey} 필터도 무변경이다.
 *
 * <p><b>{@code video.bit_rate} 도 다른 키와 같은 규칙</b>이다 — 인입 {@code BIT} 은 <b>비트레이트</b>
 * (bps 정수, 예 2050627)이며 색심도가 아니다(V16 정정 — 관제 실측값이 '24bit' 표기가 아니라 bps
 * 정수임을 2026-08-24 관제가 재확인). 인입값이 있으면 그것을 채택하고, 없거나 숫자로 파싱되지 않는
 * 레거시('24bit') 잔재면 <b>그 키만</b> ffprobe 폴백이 채운다({@link #loadIngestMeta} 참조). 소비처
 * {@code LS_DATASET_VIDEO_META.BIT_RT}({@code BIGINT}) 가 정수라 파싱 불가값은 애초에 채택하지 않는다.
 *
 * <p>probe 결과를 아래 키로 매핑해 멱등 upsert 한다. (RAW_SN, META_KEY) UK 하에서
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

    /**
     * ffprobe 가 채울 수 있는 전체 키 — 이 전부가 인입값으로 이미 채워졌다면 probe 를 돌릴 이유가 없다
     * ({@link #needsProbe}).
     *
     * <p>판정 기준은 <b>"probe 가 채울 수 있는 키(6종)"</b>다. 인입이 {@link #KEY_BIT_RATE} 를 포함해
     * 6종을 전부 유효값으로 채운 영상은 여기서 false 가 되어 NAS 파일 접근·ffprobe 실행을 통째로
     * 건너뛴다(중복 측정 제거). 인입 {@code BIT} 이 없거나 파싱 불가('24bit' 레거시)면 그 키가 빠져
     * 6종 미달이 되고, probe 가 돌아 {@code video.bit_rate} 를 폴백으로 채운다(영구 결손 방지).
     */
    private static final List<String> PROBE_KEYS = List.of(
            KEY_FPS, KEY_CODEC, KEY_BIT_RATE, KEY_DURATION_MS, KEY_FILESIZE, KEY_RESOLUTION);

    /** {@code LS_DATA_META.META_VL} 컬럼 길이(VARCHAR(2000)) — 외부 ffprobe 문자열 저장 전 길이 방어. */
    static final int META_VL_MAX = 2000;

    /** ffprobe {@code duration_ms} → {@code LS_DATA_RAW.VDO_LEN_SEC}(초) 환산 제수. */
    private static final double MILLIS_PER_SECOND = 1000.0;

    /** 인입 {@code VDO_LEN_SEC}(초) → {@code video.duration_ms}(ms) 환산 승수. */
    private static final BigDecimal MILLIS_PER_SECOND_EXACT = BigDecimal.valueOf(1000L);

    /**
     * 인입 {@code RESL} 채택 형식 — 소비처({@code DatasetVideoMetaSnapshotService#parseResolution})가
     * {@code "WIDTHxHEIGHT"} 를 분해해 가로/세로/화면비를 만든다. 등급 표기('FHD'·'4K')가 들어오면
     * 그 파생이 전부 null 이 되므로 형식이 맞는 값만 채택하고 나머지는 ffprobe 에 맡긴다(CWE-20).
     */
    private static final Pattern RESOLUTION_PATTERN = Pattern.compile("^\\d{1,6}[xX]\\d{1,6}$");

    private final LsDataMetaRepository metaRepository;
    private final VideoRepository videoRepository;
    private final LsDataIngestRepository ingestRepository;

    /**
     * probe 결과를 {@code video.*} 메타로 멱등 upsert 한다.
     *
     * <p>각 키를 {@link LsDataMetaRepository#upsertMeta} 원자 호출로 저장한다. (RAW_SN, META_KEY) UK
     * 충돌은 PostgreSQL {@code ON CONFLICT} 가 단일 문장 내에서 원자적으로 해소하므로 read-then-write
     * race(CWE-362) 가 존재하지 않는다 — 동시 스레드가 같은 키를 먼저 insert 해도 예외 없이 값이 갱신된다.
     * 상위 파이프라인 무중단(graceful) 격리는 {@code AsyncVideoMetaRunner} 가 담당한다.
     *
     * <p>인입값이 없는 경로(파생영상 등) 전용 편의 오버로드다 — 인입 병합은
     * {@link #upsertVideoMeta(Long, Map, VideoMeta)} 가 담당한다.
     *
     * @param rawSn 대상 영상 PK (LS_DATA_RAW.RAW_SN)
     * @param meta  ffprobe 추출 결과. null 필드는 저장하지 않는다.
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public void upsertVideoMeta(Long rawSn, VideoMeta meta) {
        upsertVideoMeta(rawSn, Map.of(), meta);
    }

    /**
     * 관제 인입값(우선)과 probe 결과(폴백)를 <b>키 단위로 병합</b>해 {@code video.*} 메타로 멱등 upsert 한다.
     *
     * <p>병합 규칙은 단순하다 — 인입값을 먼저 깔고, probe 결과는 <b>비어 있는 키에만</b> 채운다
     * ({@code putIfAbsent}). 그래서 인입이 일부만 채운 영상도 나머지 키를 잃지 않는다.
     *
     * <p>{@code LS_DATA_RAW.VDO_LEN_SEC} back-fill 은 <b>병합 결과</b>의 {@code duration_ms} 를 쓴다 —
     * 인입이 이겼든 probe 가 이겼든 실제 적재된 길이와 한 값으로 유지된다(두 컬럼이 갈리지 않는다).
     *
     * @param rawSn        대상 영상 PK (LS_DATA_RAW.RAW_SN)
     * @param ingestValues {@link #loadIngestMeta} 결과. null·빈 맵이면 probe 단독(종전 동작).
     * @param meta         probe 추출 결과. null(미실행/실패)이면 인입값만 적재한다.
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public void upsertVideoMeta(Long rawSn, Map<String, String> ingestValues, VideoMeta meta) {
        if (rawSn == null) {
            return;
        }
        Map<String, String> desired = new LinkedHashMap<>();
        if (ingestValues != null) {
            desired.putAll(ingestValues);
        }
        if (meta != null) {
            // 키 단위 폴백 — 인입이 채우지 못한 키만 probe 값으로 메운다.
            buildDesired(meta).forEach(desired::putIfAbsent);
        }
        if (desired.isEmpty()) {
            log.debug("[VideoMeta] no extractable field rawSn={} — skip", rawSn);
            return;
        }
        desired.forEach((key, value) -> metaRepository.upsertMeta(rawSn, key, value));
        log.info("[VideoMeta] stored rawSn={} keys={} fromIngest={}",
                rawSn, desired.size(), ingestValues == null ? 0 : ingestValues.size());
        // 같은 REQUIRES_NEW 트랜잭션에서 VDO_LEN_SEC 를 함께 back-fill(단일 커밋, 아래 Javadoc 참조).
        backfillDurationSec(rawSn, storedDurationMs(desired));
    }

    /**
     * probe 를 실행할 필요가 있는지 판정한다 — 인입값이 {@link #PROBE_KEYS} 를 <b>전부</b> 채웠으면 false.
     *
     * <p>여기서 false 가 나오면 호출자({@code AsyncVideoMetaRunner})는 NAS 파일 접근과 ffprobe 실행을
     * 통째로 건너뛴다. 판정을 호출자가 아니라 이 서비스가 갖는 이유는, 키 집합이 바뀔 때 두 곳이
     * 갈라지지 않게 하기 위해서다.
     *
     * @param ingestValues {@link #loadIngestMeta} 결과. null 이면 true(측정 필요).
     */
    public static boolean needsProbe(Map<String, String> ingestValues) {
        return ingestValues == null || !ingestValues.keySet().containsAll(PROBE_KEYS);
    }

    /**
     * 관제 인입 행({@code LS_DATA_INGEST})에서 {@code video.*} 메타로 쓸 수 있는 값을 추린다.
     *
     * <p>인입 행이 없으면(파생영상 — 저작도구가 만들어 인입 자체가 없다) <b>빈 맵</b>을 돌려주고,
     * 호출자는 종전대로 전량 ffprobe 로 채운다.
     *
     * <h3>관제 수신값은 신뢰 경계 밖이다 (CWE-20)</h3>
     * <p>각 값은 <b>소비처가 요구하는 형식·범위</b>를 통과한 것만 채택한다 — fps 는 양수 유한 실수,
     * 길이·파일크기는 양수, 해상도는 {@code WIDTHxHEIGHT}. 통과하지 못한 키는 담지 않으므로
     * 그 키만 ffprobe 폴백으로 넘어간다(전부 버리지 않는다). 형식 위반은 rawSn 만 남겨 WARN 하고
     * <b>값 자체는 로그에 넣지 않는다</b>(CWE-117/359 — 관제 자유텍스트).
     *
     * @param rawSn 대상 영상 PK. null 이면 빈 맵.
     */
    @Transactional(value = "controlTransactionManager", readOnly = true,
            propagation = Propagation.REQUIRES_NEW)
    public Map<String, String> loadIngestMeta(Long rawSn) {
        if (rawSn == null) {
            return Map.of();
        }
        LsDataIngest ingest = ingestRepository.findLatestByRawSn(rawSn).orElse(null);
        if (ingest == null) {
            log.debug("[VideoMeta] no ingest row rawSn={} — probe only", rawSn);
            return Map.of();
        }
        Map<String, String> values = new LinkedHashMap<>();
        putIfPresent(values, KEY_FPS, positiveDecimalText(ingest.getFps(), rawSn, KEY_FPS));
        putIfPresent(values, KEY_CODEC, boundedText(ingest.getVdoCdc()));
        putIfPresent(values, KEY_DURATION_MS, durationMillisText(ingest.getVdoLenSec(), rawSn));
        putIfPresent(values, KEY_FILESIZE, positiveLongText(ingest.getFileSz(), rawSn, KEY_FILESIZE));
        putIfPresent(values, KEY_RESOLUTION, resolutionText(ingest.getResl(), rawSn));
        // 인입 BIT 은 비트레이트(bps 정수, V16 정정). 인입값 우선, 파싱 불가·null·음수는 skip → ffprobe 폴백.
        // [design: ERD-012]
        putIfPresent(values, KEY_BIT_RATE, bitRateText(ingest.getBit(), rawSn));
        return values;
    }

    private static void putIfPresent(Map<String, String> target, String key, String value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    /** fps — 양수 유한 실수 문자열만 채택(소비처 {@code VideoFpsResolver} 가 double 로 파싱). */
    private static String positiveDecimalText(String raw, Long rawSn, String key) {
        String text = boundedText(raw);
        if (text == null) {
            return null;
        }
        try {
            double value = Double.parseDouble(text);
            if (value > 0 && Double.isFinite(value)) {
                return text;
            }
        } catch (NumberFormatException ignored) {
            // 아래 공통 WARN 으로 처리 — 값은 로그에 넣지 않는다.
        }
        log.warn("[VideoMeta] ingest value rejected rawSn={} key={} — probe fallback", rawSn, key);
        return null;
    }

    /** 파일 크기 — 양수만 채택(소비처가 {@code Long} 으로 파싱). */
    private static String positiveLongText(Long value, Long rawSn, String key) {
        if (value == null) {
            return null;
        }
        if (value <= 0) {
            log.warn("[VideoMeta] ingest value rejected rawSn={} key={} — probe fallback", rawSn, key);
            return null;
        }
        return String.valueOf(value);
    }

    /**
     * 비트레이트 — 인입 {@code BIT}(bps 정수 문자열, V16)을 {@code Long} 으로 파싱해 채택한다.
     *
     * <p>소비처 {@code LS_DATASET_VIDEO_META.BIT_RT} 가 {@code BIGINT} 이므로 정수(bps)만 유효하다.
     * null·공백·양수가 아닌 값·파싱 불가(레거시 '24bit' 잔재 등)는 <b>예외 없이 skip</b>(null 반환)해
     * 해당 키만 ffprobe 폴백에 넘긴다(fail-safe — 관제 수신값은 신뢰 경계 밖, CWE-20). 값 자체는
     * 로그에 넣지 않는다(CWE-117/359 — 관제 자유텍스트).
     */
    private static String bitRateText(String raw, Long rawSn) {
        String text = boundedText(raw);
        if (text == null) {
            return null;
        }
        try {
            long value = Long.parseLong(text);
            if (value > 0) {
                return String.valueOf(value);
            }
        } catch (NumberFormatException ignored) {
            // 아래 공통 WARN 으로 처리 — 값은 로그에 넣지 않는다.
        }
        log.warn("[VideoMeta] ingest value rejected rawSn={} key={} — probe fallback", rawSn, KEY_BIT_RATE);
        return null;
    }

    /**
     * 영상 길이 — 인입은 <b>초</b>({@code VDO_LEN_SEC}), 메타 키는 <b>ms</b>({@code video.duration_ms}) 다.
     * 단위 변환은 {@code ×1000} 이며 {@link BigDecimal} 로 계산해 부동소수 오차·오버플로를 배제한다
     * ({@code longValueExact} 가 초과 시 예외 → 채택하지 않고 probe 폴백).
     */
    private static String durationMillisText(BigDecimal seconds, Long rawSn) {
        if (seconds == null || seconds.signum() <= 0) {
            return null;
        }
        try {
            long millis = seconds.multiply(MILLIS_PER_SECOND_EXACT)
                    .setScale(0, RoundingMode.HALF_UP)
                    .longValueExact();
            return millis > 0 ? String.valueOf(millis) : null;
        } catch (ArithmeticException e) {
            log.warn("[VideoMeta] ingest duration out of range rawSn={} — probe fallback", rawSn);
            return null;
        }
    }

    /** 해상도 — {@code WIDTHxHEIGHT} 형식만 채택하고 소비처 파싱 규약대로 소문자 {@code x} 로 정규화한다. */
    private static String resolutionText(String raw, Long rawSn) {
        String text = boundedText(raw);
        if (text == null) {
            return null;
        }
        if (!RESOLUTION_PATTERN.matcher(text).matches()) {
            log.warn("[VideoMeta] ingest value rejected rawSn={} key={} — probe fallback",
                    rawSn, KEY_RESOLUTION);
            return null;
        }
        return text.toLowerCase();
    }

    /** 공백 정리 + {@code META_VL}(2000) 길이 방어. 비어 있으면 null(= 미채택). */
    private static String boundedText(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String text = raw.trim();
        return text.length() > META_VL_MAX ? text.substring(0, META_VL_MAX) : text;
    }

    /** 병합 결과에 실제로 적재된 {@code duration_ms} — back-fill 이 참조하는 단일 값. */
    private static Long storedDurationMs(Map<String, String> desired) {
        String stored = desired.get(KEY_DURATION_MS);
        if (stored == null) {
            return null;
        }
        try {
            return Long.valueOf(stored);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 적재된 {@code duration_ms}(인입값 또는 ffprobe) 를 {@code LS_DATA_RAW.VDO_LEN_SEC}(초)로
     * 역류 back-fill 한다.
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
