package kr.co.cudo.authoring.portal.service;

import jakarta.persistence.Column;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.dataset.export.ExportPrivacyPolicy;
import kr.co.cudo.authoring.dataset.util.ShootingEnvironmentVocabulary;
import kr.co.cudo.authoring.dataset.util.TimeOfDaySeasonDeriver;
import kr.co.cudo.authoring.portal.dto.PortalMetaScope;
import kr.co.cudo.authoring.portal.dto.PortalMetaSource;
import kr.co.cudo.authoring.video.entity.LsDataRaw;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * 원장의 <b>컬럼</b>에서 오는 메타 축을 포털 계약의 <b>키/값</b>으로 옮기는 단일 지점.
 *
 * <h3>★ 다섯 축의 저장 모양은 하나가 아니다</h3>
 * <p>시계열 메타만 메타 원장의 키/값이라 Load·저장이 1:1 이고, 이벤트 어노테이션은 구조체라 별도
 * 창구가 담당한다. 나머지 셋 — <b>촬영환경 · 프레임 설명 · 개인정보 판정</b> — 은 원장의 컬럼이라
 * 키/값이 아니다. 그래서 조회 시점에 키/값으로 바꿔 내리고 저장 시점에 되돌린다.
 * <b>그 변환 규칙은 이 enum 하나가 소유하며 읽기·쓰기가 같은 규칙을 쓴다</b> — 두 곳에 두면 어긋난다.
 *
 * <h3>★★ 원소를 식별하는 것은 키 단독이 아니라 (축, 키) 쌍이다</h3>
 * <p>개인정보 세 키는 <b>영상 축과 프레임 축 양쪽에 같은 이름</b>으로 있다({@code LS_DATA_RAW} 와
 * {@code LS_DATA_SRC} 가 각각 같은 이름의 컬럼을 갖는다). 키만 보면 영상 값과 프레임 값이 한 목록에서
 * 섞이고, 저장 시 영상 축 값이 프레임에 매달린다. 그래서 이 enum 의 상수는 <b>축까지 포함해</b>
 * 열 개이며 조회 진입점 {@link #of(PortalMetaScope, String)} 도 축을 함께 받는다.
 *
 * <h3>★ 자동 계산 규칙과 허용값을 복제하지 않는다</h3>
 * <p>시간대·계절 파생은 {@link TimeOfDaySeasonDeriver}, 촬영환경 허용값은
 * {@link ShootingEnvironmentVocabulary}, 개인정보 프리필 상수는 {@link ExportPrivacyPolicy} 가
 * <b>소유</b>한다. 여기서는 부르기만 한다 — 복제하면 두 번째 진실원이 되어 소유자가 규칙을 바꾸는 날
 * 포털만 조용히 뒤처진다(이 저장소가 개인정보 3필드 상수를 복제했다가 화면과 산출물이 갈린 사고를
 * 이미 겪었다).
 *
 * <h3>★★★ 저장 동작은 재사용하지 않는다</h3>
 * <p>같은 컬럼을 고치는 <b>내부 저장 창구</b>({@code EnvironmentMetaService}·
 * {@code VideoPrivacyMetaService}·{@code FramePrivacyMetaService}·{@code FrameDescriptionService})는
 * 저장하면서 원장 컬럼을 직접 고치고, 재검토 표시를 세우고, 관제 통지를 발행하고, 승인 시점 동결본을
 * 다시 굳힌다. 포털이 그 창구를 부르면 이 기능의 최상위 불변 둘을 <b>한 번에</b> 위반한다.
 * 이 위반은 「중복 구현을 피하자」는 가장 자연스러운 판단에서 나오므로 금지로 못박는다.
 * <b>재사용하는 것은 읽기와 규칙뿐</b>이고 저장 동작은 재사용하지 않는다.
 *
 * @design ERD-018
 * @design API-234
 * @design API-235
 */
public enum PortalColumnMetaField {

    /** 촬영환경 — 날씨({@code LS_DATA_RAW.WTHR_NM}). 자동 출처가 없어 수동값이 곧 전부다. */
    ENV_WEATHER(Keys.ENV_WEATHER, PortalMetaScope.VIDEO, Target.VIDEO_ROW),
    /** 촬영환경 — 시간대({@code LS_DATA_RAW.DAY_NGT_CD}). 미저장 시 촬영일시에서 파생한다. */
    ENV_TIME_OF_DAY(Keys.ENV_TIME_OF_DAY, PortalMetaScope.VIDEO, Target.VIDEO_ROW),
    /** 촬영환경 — 계절({@code LS_DATA_RAW.SESN_CD}). 미저장 시 촬영일시에서 파생한다. */
    ENV_SEASON(Keys.ENV_SEASON, PortalMetaScope.VIDEO, Target.VIDEO_ROW),

    /** 영상 축 개인정보 — 익명({@code LS_DATA_RAW.ANONY_INCL_YN}). */
    VIDEO_ANONYMITY(Keys.PRIVACY_ANONYMITY, PortalMetaScope.VIDEO, Target.VIDEO_ROW),
    /** 영상 축 개인정보 — 가명({@code LS_DATA_RAW.PSDO_INCL_YN}). */
    VIDEO_PSEUDONYMITY(Keys.PRIVACY_PSEUDONYMITY, PortalMetaScope.VIDEO, Target.VIDEO_ROW),
    /** 영상 축 개인정보 — 포함여부({@code LS_DATA_RAW.PRVC_INCL_YN}). */
    VIDEO_PRIVACY_INCLUDED(Keys.PRIVACY_PRIVACY_INCLUDED, PortalMetaScope.VIDEO, Target.VIDEO_ROW),

    /** 프레임 설명({@code LS_DATA_SRC.FRM_EXPLN}). */
    FRAME_DESCRIPTION(Keys.FRAME_DESCRIPTION, PortalMetaScope.FRAME, Target.FRAME_ROW),
    /** 프레임 축 개인정보 — 익명({@code LS_DATA_SRC.ANONY_INCL_YN}). */
    FRAME_ANONYMITY(Keys.PRIVACY_ANONYMITY, PortalMetaScope.FRAME, Target.FRAME_ROW),
    /** 프레임 축 개인정보 — 가명({@code LS_DATA_SRC.PSDO_INCL_YN}). */
    FRAME_PSEUDONYMITY(Keys.PRIVACY_PSEUDONYMITY, PortalMetaScope.FRAME, Target.FRAME_ROW),
    /** 프레임 축 개인정보 — 포함여부({@code LS_DATA_SRC.PRVC_INCL_YN}). */
    FRAME_PRIVACY_INCLUDED(Keys.PRIVACY_PRIVACY_INCLUDED, PortalMetaScope.FRAME, Target.FRAME_ROW);

    /**
     * 키 어휘 — 소유자는 데이터 계층({@code ERD-018} 의 {@code META_KEY} 정의)이다. 접두는
     * <b>의미 분류일 뿐 축이 아니다</b> — 축은 오버레이의 프레임 참조 컬럼이 가른다.
     */
    public static final class Keys {
        public static final String ENV_WEATHER = "env.weather";
        public static final String ENV_TIME_OF_DAY = "env.timeOfDay";
        public static final String ENV_SEASON = "env.season";
        public static final String PRIVACY_ANONYMITY = "privacy.anonymity";
        public static final String PRIVACY_PSEUDONYMITY = "privacy.pseudonymity";
        public static final String PRIVACY_PRIVACY_INCLUDED = "privacy.privacyIncluded";
        public static final String FRAME_DESCRIPTION = "frame.description";

        private Keys() {
        }
    }

    /** 본인 업로드 자산에서 값이 실제로 앉는 원장 행 — 저장 실행문이 갈리는 축이다. */
    public enum Target {
        /** 영상 원장({@code LS_DATA_RAW}) 행. */
        VIDEO_ROW,
        /** 프레임 원장({@code LS_DATA_SRC}) 행. */
        FRAME_ROW
    }

    /**
     * 프레임 설명의 저장 폭 — <b>좁은 쪽</b>이다.
     *
     * <p>오버레이 저장 폭은 2000자인데 원본({@code LS_DATA_SRC.FRM_EXPLN})이 1000자라 넓은 쪽을
     * 허용하면 사용자가 자기 데이터를 내려받을 때 <b>원본과 같은 자리에 들어가지 못하는 값</b>이
     * 생긴다. 그래서 저장 검증을 좁은 쪽으로 한다.
     *
     * <p>이 값의 실제 소유자는 원장 컬럼이며 여기 상수는 그 사본이다 — 사본이 어긋나면 조용히
     * 실패하므로 <b>시험이 원장 컬럼 정의와 대조</b>한다({@code PortalColumnMetaFieldTest}).
     */
    public static final int FRAME_DESCRIPTION_MAX_LENGTH = 1000;

    /** 개인정보 판정 허용값 — 원장 창구의 {@code @Pattern("\\A[YN]\\z")} 과 같은 닫힌 두 값. */
    private static final Set<String> ALLOWED_YN = Set.of("Y", "N");

    private static final Set<String> ALL_KEYS = Arrays.stream(values())
            .map(PortalColumnMetaField::metaKey)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

    private final String metaKey;
    private final PortalMetaScope scope;
    private final Target target;

    PortalColumnMetaField(String metaKey, PortalMetaScope scope, Target target) {
        this.metaKey = metaKey;
        this.scope = scope;
        this.target = target;
    }

    public String metaKey() {
        return metaKey;
    }

    public PortalMetaScope scope() {
        return scope;
    }

    public Target target() {
        return target;
    }

    /**
     * (축, 키) 쌍으로 찾는다 — <b>키만으로 찾지 않는다</b>(개인정보 세 키가 두 축에 같은 이름으로
     * 있어 키만 보면 영상 값과 프레임 값이 섞인다).
     *
     * @return 그 쌍이 컬럼 축이면 해당 상수, 아니면 비어 있음
     */
    public static Optional<PortalColumnMetaField> of(PortalMetaScope scope, String metaKey) {
        if (scope == null || metaKey == null) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(f -> f.scope == scope && f.metaKey.equals(metaKey))
                .findFirst();
    }

    /**
     * 이 키가 <b>축을 가리지 않고</b> 컬럼 축의 키인가.
     *
     * <p>목록 배분({@code PortalMetaKeyPolicy})처럼 축이 주어지지 않는 자리에서 쓴다. 축까지 맞는지는
     * {@link #of} 가 판정하며, 축이 어긋난 저장 요청은 저장 창구가 거부한다 — 조용히 한쪽으로 접으면
     * 사용자가 저장했다고 믿는 값이 <b>다시는 읽히지 않는 자리</b>에 들어간다.
     */
    public static boolean isColumnKey(String metaKey) {
        return metaKey != null && ALL_KEYS.contains(metaKey);
    }

    /** 컬럼 축 키 전부(중복 제거) — 목록 배분 시험이 전수를 훑는 데 쓴다. */
    public static Set<String> allKeys() {
        return ALL_KEYS;
    }

    /** 유효값과 그 출처 — 원본 쪽 사실이다(본인 오버레이는 여기 반영되지 않는다). */
    public record Effective(String value, PortalMetaSource source) {
    }

    /**
     * 원본의 <b>유효값</b> — 수동 저장값이 있으면 그 값, 없으면 자동으로 계산한 값, 그것도 없으면 없음.
     *
     * <p>이것이 저장 시 <b>승격 방어의 비교 대상</b>이다(받은 값이 이것과 같으면 아무것도 쌓지 않는다).
     */
    public Effective effective(LsDataRaw raw, LsDataSrc frame) {
        String stored = stored(raw, frame);
        if (stored != null && !stored.isBlank()) {
            return new Effective(stored.trim(), PortalMetaSource.MANUAL);
        }
        String derived = derived(raw);
        if (derived != null) {
            return new Effective(derived, PortalMetaSource.DERIVED);
        }
        return new Effective(null, PortalMetaSource.NONE);
    }

    /** 원장 컬럼에 실제로 적재된 값(사람이 고른 값). */
    public String stored(LsDataRaw raw, LsDataSrc frame) {
        return switch (this) {
            case ENV_WEATHER -> raw == null ? null : raw.getWthrNm();
            case ENV_TIME_OF_DAY -> raw == null ? null : raw.getDayNgtCd();
            case ENV_SEASON -> raw == null ? null : raw.getSesnCd();
            case VIDEO_ANONYMITY -> raw == null ? null : raw.getAnonyInclYn();
            case VIDEO_PSEUDONYMITY -> raw == null ? null : raw.getPsdoInclYn();
            case VIDEO_PRIVACY_INCLUDED -> raw == null ? null : raw.getPrvcInclYn();
            case FRAME_DESCRIPTION -> frame == null ? null : frame.getFrmExpln();
            case FRAME_ANONYMITY -> frame == null ? null : frame.getAnonyInclYn();
            case FRAME_PSEUDONYMITY -> frame == null ? null : frame.getPsdoInclYn();
            case FRAME_PRIVACY_INCLUDED -> frame == null ? null : frame.getPrvcInclYn();
        };
    }

    /**
     * 저장값이 없을 때 자동으로 계산되는 값. 계산 규칙의 소유자를 <b>그대로 부른다</b>.
     *
     * <p>날씨와 프레임 설명은 자동 출처가 <b>없다</b> — 지어내지 않고 없음으로 둔다.
     */
    public String derived(LsDataRaw raw) {
        return switch (this) {
            case ENV_WEATHER, FRAME_DESCRIPTION -> null;
            case ENV_TIME_OF_DAY -> raw == null ? null : TimeOfDaySeasonDeriver.dayNight(raw.getShtDt());
            case ENV_SEASON -> raw == null ? null : TimeOfDaySeasonDeriver.season(raw.getShtDt());
            case VIDEO_ANONYMITY, FRAME_ANONYMITY -> ExportPrivacyPolicy.DEID_DEFAULT_ANONYMITY;
            case VIDEO_PSEUDONYMITY, FRAME_PSEUDONYMITY -> ExportPrivacyPolicy.DEID_DEFAULT_PSEUDONYMITY;
            case VIDEO_PRIVACY_INCLUDED, FRAME_PRIVACY_INCLUDED ->
                    ExportPrivacyPolicy.DEID_DEFAULT_PRIVACY_INCLUDED;
        };
    }

    /**
     * 저장값 허용 규칙 (CWE-20/79) — 위반이면 400.
     *
     * <p>비어 있는 값({@code null})은 <b>지움</b>이라 통과한다. 거부 메시지에 <b>요청받은 값을 되돌려
     * 싣지 않는다</b> — 전역 예외 처리기가 메시지를 그대로 로깅하므로 개행이 섞인 값을 echo 하면
     * 로그 위조가 된다(CWE-117/209).
     */
    public void validate(String value) {
        if (value == null) {
            return;
        }
        switch (this) {
            case ENV_WEATHER -> requireAllowed(value, ShootingEnvironmentVocabulary.WEATHERS);
            case ENV_TIME_OF_DAY -> requireAllowed(value, ShootingEnvironmentVocabulary.TIME_OF_DAYS);
            case ENV_SEASON -> requireAllowed(value, ShootingEnvironmentVocabulary.SEASONS);
            case VIDEO_ANONYMITY, VIDEO_PSEUDONYMITY, VIDEO_PRIVACY_INCLUDED,
                 FRAME_ANONYMITY, FRAME_PSEUDONYMITY, FRAME_PRIVACY_INCLUDED ->
                    requireAllowed(value, ALLOWED_YN);
            case FRAME_DESCRIPTION -> {
                if (value.length() > FRAME_DESCRIPTION_MAX_LENGTH) {
                    throw new CustomException(ErrorCode.INVALID_INPUT,
                            "프레임 설명이 저장할 수 있는 길이를 넘습니다.");
                }
            }
        }
    }

    private void requireAllowed(String value, Set<String> allowed) {
        if (!allowed.contains(value)) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "허용되지 않은 값입니다.");
        }
    }

    /**
     * 원장 컬럼 폭의 <b>사본이 어긋나지 않았는지</b> 확인하는 근거 — 시험이 이 값을 읽어 상수와 맞춘다.
     *
     * @return {@code LS_DATA_SRC.FRM_EXPLN} 의 실제 선언 폭
     */
    static int declaredFrameDescriptionColumnLength() {
        try {
            Column column = LsDataSrc.class.getDeclaredField("frmExpln").getAnnotation(Column.class);
            return column.length();
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException("프레임 설명 컬럼 정의를 찾지 못했다.", e);
        }
    }
}
