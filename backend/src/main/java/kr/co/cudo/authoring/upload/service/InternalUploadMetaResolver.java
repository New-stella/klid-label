package kr.co.cudo.authoring.upload.service;

import kr.co.cudo.authoring.upload.service.UploadMediaProbe.MediaMeta;
import kr.co.cudo.authoring.video.dto.ResolvedIngestMeta;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.regex.Pattern;

/**
 * ffprobe 측정값 → 인입 원장({@code LS_DATA_INGEST}) 기술메타 8컬럼 <b>해석·파생·검증의 판정
 * 단일 원천</b>. 이 판정을 다른 클래스에 복제하지 말 것 — 복제하면 한쪽만 갱신돼 어긋난다.
 *
 * <p>ffprobe 출력은 <b>신뢰 경계 밖</b>이다(CWE-20). 컨테이너가 신고한 값은 형식·범위는 물론
 * DDL 길이({@code VDO_CDC VARCHAR(20)} · {@code FPS VARCHAR(10)} · {@code RESL}/{@code ASPRT_RT
 * VARCHAR(20)} · {@code NUMERIC(10)})까지 통과한 것만 채택한다. <b>검증 실패는 예외가 아니라 그
 * 컬럼만 미채택</b>이며, 다른 컬럼까지 버리지 않는다({@code VideoMetaService.loadIngestMeta} 와
 * 동일 규약). 특히 코덱명은 길이 초과 시 <b>절단하지 않고 버린다</b> — 절단하면 "틀린 코덱명"이
 * 확정 저장되기 때문이다.
 *
 * <p><b>"그 컬럼만 미채택"의 명시적 예외 2가지</b>(둘 다 의도이며 회귀 테스트가 고정하고 있다):
 * <ol>
 *   <li><b>해상도 4종은 한 덩어리로 게이팅</b>한다 — {@code width}·{@code height} 중 하나라도 0 이면
 *       {@code wdth}/{@code vrtc}/{@code resl}/GCD 파생 {@code asprtRt} 를 <b>함께</b> 버린다
 *       ({@code MediaMeta(1920, 0, ...)} → {@code wdth == null}). 반쪽 해상도는 의미가 없고, 한쪽만
 *       적재하면 "1920 폭에 높이 미상"이라는 해석 불가능한 행이 남기 때문이다
 *       (가드: {@code 너비만_있고_높이가_0이면_둘_다_미채택한다}).</li>
 *   <li><b>길이 미채택은 프레임수 파생까지 연쇄 차단</b>한다 — 0.5초 미만 영상(예 {@code 499ms})은
 *       초 반올림이 0 이라 {@code vdoLenSec} 가 미채택되고, {@link #frameCount} 파생이
 *       {@code vdoLenSec != null} 을 요구하므로 실제 프레임이 약 15장이어도 {@code frmeCnt} 까지
 *       비게 된다. 30초 이상 영상이 대상인 사업 특성상 도달 빈도가 없다시피 하고, 길이가 미상인
 *       상태에서 파생 프레임수만 적재하면 근거 없는 값이 되므로 그대로 둔다.</li>
 * </ol>
 *
 * <p>{@code PXL}(화소)·{@code BIT}(색심도)는 <b>어떤 경우에도 채우지 않는다</b>. 표기 규약이
 * 정의돼 있지 않아 무엇을 넣든 지어낸 값이 되기 때문이며(프로젝트 "값을 지어내지 않는다" 원칙),
 * 그 의지를 코드로 못 박기 위해 {@link ResolvedIngestMeta} 에 <b>필드 자체를 두지 않는다</b>.
 *
 * <p>부동소수 오차·오버플로가 결과를 바꾸면 안 되는 계산(초 환산·프레임수 파생)은
 * {@link BigDecimal} + {@code longValueExact()} 로 수행하고 {@link ArithmeticException} 은
 * 미채택으로 처리한다({@code VideoMetaService.durationMillisText} 와 같은 패턴).
 *
 * <p>로그에는 필드명만 남기고 측정 원문 값·파일 경로는 남기지 않는다(CWE-209).
 */
@Slf4j
public final class InternalUploadMetaResolver {

    /** {@code VDO_CDC VARCHAR(20)} */
    private static final int MAX_CODEC_LENGTH = 20;
    /** {@code FPS VARCHAR(10)} */
    private static final int MAX_FPS_LENGTH = 10;
    /** {@code RESL VARCHAR(20)} · {@code ASPRT_RT VARCHAR(20)} */
    private static final int MAX_TEXT_LENGTH = 20;
    /** {@code NUMERIC(10)} — 정수 10자리 */
    private static final BigDecimal NUMERIC_10_EXCLUSIVE_MAX = BigDecimal.TEN.pow(10);
    /** fps 표기 소수 자릿수 (29.97). */
    private static final int FPS_SCALE = 2;

    private static final BigDecimal MILLIS_PER_SECOND = BigDecimal.valueOf(1000L);

    /** ffprobe {@code display_aspect_ratio} 허용 형식 — {@code 16:9}. */
    private static final Pattern ASPECT_RATIO_PATTERN = Pattern.compile("^\\d{1,9}:\\d{1,9}$");
    /** 코덱명 허용 문자 — ffmpeg 코덱명은 영숫자/밑줄/점/하이픈 조합이다. */
    private static final Pattern CODEC_PATTERN = Pattern.compile("^[A-Za-z0-9_.\\-]+$");

    private static final ResolvedIngestMeta NOTHING =
            new ResolvedIngestMeta(null, null, null, null, null, null, null, null);

    private InternalUploadMetaResolver() {
    }

    /**
     * 측정값을 인입 8컬럼에 실을 수 있는 값으로 해석한다.
     *
     * @param meta ffprobe 원시 측정값. {@code null} 이면 전량 미채택으로 돌아온다.
     * @return 채택된 값만 담긴 결과. 미채택 필드는 {@code null} — 호출자(Phase 2)는 그 컬럼을
     * <b>건드리지 않는다</b>(사용자 입력 보존 · R4).
     * @req R2
     */
    public static ResolvedIngestMeta resolve(MediaMeta meta) {
        if (meta == null) {
            return NOTHING;
        }

        BigDecimal vdoLenSec = durationSeconds(meta.durationMs());
        String fps = fpsText(meta.fps());
        String vdoCdc = codecText(meta.codecName());

        boolean hasDimensions = meta.width() > 0 && meta.height() > 0;
        BigDecimal wdth = hasDimensions ? numeric10(BigDecimal.valueOf(meta.width()), "wdth") : null;
        BigDecimal vrtc = hasDimensions ? numeric10(BigDecimal.valueOf(meta.height()), "vrtc") : null;
        String resl = hasDimensions
                ? bounded(meta.width() + "x" + meta.height(), MAX_TEXT_LENGTH, "resl")
                : null;

        BigDecimal frmeCnt = frameCount(meta, vdoLenSec, fps);
        String asprtRt = aspectRatio(meta, hasDimensions);

        return new ResolvedIngestMeta(vdoLenSec, fps, vdoCdc, wdth, vrtc, resl, frmeCnt, asprtRt);
    }

    // ======================== 개별 컬럼 판정 ========================

    /** ms → 초(정수, HALF_UP). 0 이하·미상·자릿수 초과는 미채택. */
    private static BigDecimal durationSeconds(Long durationMs) {
        if (durationMs == null || durationMs <= 0L) {
            return null;
        }
        BigDecimal seconds = BigDecimal.valueOf(durationMs)
                .divide(MILLIS_PER_SECOND, 0, RoundingMode.HALF_UP);
        return numeric10(seconds, "vdoLenSec");
    }

    /**
     * fps 표기 — 소수 2자리로 반올림한 뒤 후행 0·후행 소수점을 제거한다({@code 29.97}, {@code 30}).
     * 반올림 결과가 0 이 되는 미세값(예 0.001)은 "0" 이 아니라 미채택으로 처리한다.
     */
    private static String fpsText(Double fps) {
        if (fps == null || !Double.isFinite(fps) || fps <= 0.0) {
            return null;
        }
        BigDecimal rounded = BigDecimal.valueOf(fps).setScale(FPS_SCALE, RoundingMode.HALF_UP);
        if (rounded.signum() <= 0) {
            return reject("fps");
        }
        return bounded(rounded.stripTrailingZeros().toPlainString(), MAX_FPS_LENGTH, "fps");
    }

    /** 코덱명 — 공백·비허용문자·길이 초과는 <b>절단하지 않고</b> 미채택. */
    private static String codecText(String codecName) {
        if (codecName == null) {
            return null;
        }
        String value = codecName.trim();
        if (value.isEmpty() || !CODEC_PATTERN.matcher(value).matches()) {
            return reject("vdoCdc");
        }
        return bounded(value, MAX_CODEC_LENGTH, "vdoCdc");
    }

    /**
     * 프레임수 — 컨테이너가 신고한 {@code nb_frames} 를 우선 채택하고, 없거나 비정상(0 이하)이면
     * {@code 길이 × fps} 로 <b>파생</b>한다.
     *
     * <p>파생은 길이·fps 가 <b>둘 다 채택 가능할 때만</b> 성립한다(채택 여부로 게이팅). 계산 자체는
     * 반올림 손실을 줄이기 위해 채택 표기값이 아니라 원시 ms·원시 fps 로 수행한다.
     * VFR(가변 프레임레이트) 영상에서는 파생값이 실제와 미세하게 다를 수 있으나, 프레임 추출은
     * 이 값이 아니라 fps 를 쓰므로 파이프라인에 영향이 없다.
     *
     * <p><b>연쇄 차단 주의</b> — 이 게이팅 때문에 길이가 미채택되면 프레임수도 함께 빈다. 0.5초 미만
     * 영상이 그 경로다(클래스 Javadoc "예외 2" 참조).
     *
     * <p>{@link ArithmeticException} 방어는 <b>현재 입력 도메인에서는 도달 불가</b>다 — 제수 1000
     * (=2³·5³)은 항상 종료소수라 {@code divide} 가 무한소수 예외를 던지지 않는다. 그럼에도 남겨 두는
     * 이유는 제수(ms 환산 단위)나 스케일 정책이 바뀌는 순간 이 경로가 되살아나기 때문이며, 그때
     * 예외가 트랜잭션을 깨는 대신 "그 컬럼만 미채택"으로 남게 하려는 것이다. 커버리지에 잡히지
     * 않는다고 지우지 말 것.
     */
    private static BigDecimal frameCount(MediaMeta meta, BigDecimal vdoLenSec, String fps) {
        Long nbFrames = meta.nbFrames();
        if (nbFrames != null && nbFrames > 0L) {
            return numeric10(BigDecimal.valueOf(nbFrames), "frmeCnt");
        }
        if (vdoLenSec == null || fps == null || meta.durationMs() == null || meta.fps() == null) {
            return null;
        }
        try {
            BigDecimal seconds = BigDecimal.valueOf(meta.durationMs()).divide(MILLIS_PER_SECOND);
            BigDecimal derived = seconds.multiply(BigDecimal.valueOf(meta.fps()))
                    .setScale(0, RoundingMode.HALF_UP);
            return derived.signum() > 0 ? numeric10(derived, "frmeCnt") : null;
        } catch (ArithmeticException e) {
            return reject("frmeCnt");
        }
    }

    /**
     * 종횡비 — ffprobe {@code display_aspect_ratio} 를 우선 채택하고, 미상이거나 형식·값이
     * 비정상({@code N/A} · {@code 0:1})이면 너비:높이의 <b>최대공약수 축약</b>으로 만든다.
     */
    private static String aspectRatio(MediaMeta meta, boolean hasDimensions) {
        String declared = normalizeDeclaredAspectRatio(meta.displayAspectRatio());
        if (declared != null) {
            return declared;
        }
        if (!hasDimensions) {
            return null;
        }
        int gcd = gcd(meta.width(), meta.height());
        return bounded((meta.width() / gcd) + ":" + (meta.height() / gcd), MAX_TEXT_LENGTH, "asprtRt");
    }

    /** 신고된 종횡비가 형식·범위·길이를 통과하면 그 값을, 아니면 null(→ GCD 폴백)을 돌려준다. */
    private static String normalizeDeclaredAspectRatio(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (!ASPECT_RATIO_PATTERN.matcher(value).matches()) {
            return null;
        }
        int colon = value.indexOf(':');
        long left = Long.parseLong(value.substring(0, colon));
        long right = Long.parseLong(value.substring(colon + 1));
        if (left <= 0L || right <= 0L) {
            return null;
        }
        return value.length() <= MAX_TEXT_LENGTH ? value : null;
    }

    private static int gcd(int a, int b) {
        int x = a;
        int y = b;
        while (y != 0) {
            int t = x % y;
            x = y;
            y = t;
        }
        return x == 0 ? 1 : x;
    }

    // ======================== 공통 검증 ========================

    /** {@code NUMERIC(10)} 적재 가능성 검증 — 양수 + 정수 10자리 이내. */
    private static BigDecimal numeric10(BigDecimal value, String key) {
        if (value == null || value.signum() <= 0) {
            return null;
        }
        return value.compareTo(NUMERIC_10_EXCLUSIVE_MAX) < 0 ? value : reject(key);
    }

    /** DDL 길이 검증 — 초과하면 절단하지 않고 미채택. */
    private static String bounded(String value, int maxLength, String key) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        return value.length() <= maxLength ? value : reject(key);
    }

    /**
     * 미채택 기록 — 어떤 컬럼이 걸러졌는지만 남기고 <b>측정 원문 값은 남기지 않는다</b>(CWE-209:
     * 코덱·종횡비 원문이 파일 메타에서 온 값이라 PII 를 담을 수 있다).
     */
    private static <T> T reject(String key) {
        log.debug("[Upload][Meta] probe value rejected key={}", key);
        return null;
    }
}
