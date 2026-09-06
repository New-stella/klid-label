package kr.co.cudo.authoring.portal;

import jakarta.validation.constraints.Pattern;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.dataset.dto.VideoPrivacyMetaResponse;
import kr.co.cudo.authoring.dataset.dto.VideoPrivacyMetaUpdateRequest;
import kr.co.cudo.authoring.dataset.export.ExportPrivacyPolicy;
import kr.co.cudo.authoring.dataset.util.ShootingEnvironmentVocabulary;
import kr.co.cudo.authoring.dataset.util.TimeOfDaySeasonDeriver;
import kr.co.cudo.authoring.portal.dto.PortalMetaScope;
import kr.co.cudo.authoring.portal.dto.PortalMetaSource;
import kr.co.cudo.authoring.portal.service.PortalColumnMetaField;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 컬럼 축 변환 규칙을 고정한다 — <b>사본이 원본과 어긋나는 순간</b>을 잡는 것이 목적이다.
 *
 * <p>이 클래스가 담는 값(폭·허용값·자동 계산·출처 어휘)은 전부 <b>다른 곳이 소유</b>한다. 소유자가
 * 값을 바꿨는데 여기가 따라가지 않으면 오류 없이 조용히 갈린다 — 그래서 소유자를 직접 읽어 대조한다.
 *
 * @design ERD-018, API-234, API-235
 */
class PortalColumnMetaFieldTest {

    // ==================================================== 사본 드리프트 가드

    /**
     * ★ 폭은 <b>좁은 쪽</b>이고 그 좁은 쪽의 소유자는 원장 컬럼이다. 여기 상수는 사본이라 원장이
     * 폭을 바꾸면 조용히 어긋난다 — 그러면 저장은 통과하는데 내려받은 값이 원본 자리에 들어가지 못한다.
     */
    @Test
    @DisplayName("★프레임_설명_폭_상수는_원장_컬럼_정의와_같다")
    void frameDescriptionWidthMatchesLedgerColumn() throws Exception {
        Field field = LsDataSrc.class.getDeclaredField("frmExpln");
        int declared = field.getAnnotation(jakarta.persistence.Column.class).length();

        assertThat(PortalColumnMetaField.FRAME_DESCRIPTION_MAX_LENGTH)
                .as("원장 컬럼 폭이 바뀌면 이 상수도 함께 바뀌어야 한다")
                .isEqualTo(declared);
        assertThat(declared)
                .as("오버레이 저장 폭(2000)보다 좁아야 이 규칙 자체가 의미를 갖는다")
                .isLessThan(2000);
    }

    /**
     * ★ 개인정보 허용값은 원장 창구가 소유한 닫힌 두 값이다. 그쪽이 값을 넓혔는데 여기가 그대로면
     * 포털만 정상 값을 거부한다(사본이 두 번째 진실원이 되는 이 저장소의 반복 결함 패턴).
     */
    @Test
    @DisplayName("★개인정보_허용값은_원장_창구의_규칙과_같은_두_값이다")
    void privacyAllowlistMatchesLedgerContract() throws Exception {
        Pattern pattern = VideoPrivacyMetaUpdateRequest.class
                .getDeclaredMethod("anonymity").getAnnotation(Pattern.class);
        assertThat(pattern).as("원장 창구가 허용값 규칙을 잃으면 대조할 기준이 사라진다").isNotNull();
        java.util.regex.Pattern compiled = java.util.regex.Pattern.compile(pattern.regexp());

        for (String candidate : new String[]{"Y", "N", "X", "y", "", "Y\n"}) {
            boolean ledgerAccepts = compiled.matcher(candidate).matches();
            boolean portalAccepts = accepts(PortalColumnMetaField.VIDEO_ANONYMITY, candidate);
            assertThat(portalAccepts)
                    .as("원장 규칙과 판정이 갈리면 안 된다: [%s]", candidate)
                    .isEqualTo(ledgerAccepts);
        }
    }

    /** 출처 표기는 원장 창구가 소유한다 — 리터럴을 복제하면 한쪽만 바뀌어 어긋난다. */
    @Test
    @DisplayName("출처_표기는_원장_창구의_어휘를_그대로_쓴다")
    void sourceVocabularyIsReused() {
        assertThat(PortalMetaSource.MANUAL.wireValue())
                .isEqualTo(VideoPrivacyMetaResponse.SOURCE_MANUAL);
        assertThat(PortalMetaSource.DERIVED.wireValue())
                .isEqualTo(VideoPrivacyMetaResponse.SOURCE_DERIVED);
    }

    /**
     * ★ 자동 계산 규칙의 소유자를 <b>그대로 부르는지</b> — 복제하면 소유자가 경계를 옮기는 날
     * (예: 주간 시작 시각) 포털만 다른 값을 보여 준다.
     */
    @Test
    @DisplayName("★시간대_계절_자동값은_소유자의_계산과_모든_경계에서_일치한다")
    void derivationDelegatesToOwner() {
        LocalDateTime[] samples = {
                LocalDateTime.of(2026, 3, 1, 5, 59), LocalDateTime.of(2026, 3, 1, 6, 0),
                LocalDateTime.of(2026, 6, 1, 17, 59), LocalDateTime.of(2026, 9, 1, 18, 0),
                LocalDateTime.of(2026, 12, 31, 23, 59), LocalDateTime.of(2026, 2, 1, 0, 0)};
        for (LocalDateTime at : samples) {
            LsDataRaw raw = rawWithShotAt(at);
            assertThat(PortalColumnMetaField.ENV_TIME_OF_DAY.derived(raw))
                    .isEqualTo(TimeOfDaySeasonDeriver.dayNight(at));
            assertThat(PortalColumnMetaField.ENV_SEASON.derived(raw))
                    .isEqualTo(TimeOfDaySeasonDeriver.season(at));
        }
    }

    @Test
    @DisplayName("개인정보_자동값은_비식별_산출물_기본상수를_그대로_쓴다")
    void privacyPrefillReusesExportPolicyConstants() {
        assertThat(PortalColumnMetaField.VIDEO_ANONYMITY.derived(null))
                .isEqualTo(ExportPrivacyPolicy.DEID_DEFAULT_ANONYMITY);
        assertThat(PortalColumnMetaField.FRAME_ANONYMITY.derived(null))
                .isEqualTo(ExportPrivacyPolicy.DEID_DEFAULT_ANONYMITY);
        assertThat(PortalColumnMetaField.VIDEO_PSEUDONYMITY.derived(null))
                .isEqualTo(ExportPrivacyPolicy.DEID_DEFAULT_PSEUDONYMITY);
        assertThat(PortalColumnMetaField.VIDEO_PRIVACY_INCLUDED.derived(null))
                .isEqualTo(ExportPrivacyPolicy.DEID_DEFAULT_PRIVACY_INCLUDED);
    }

    @Test
    @DisplayName("촬영환경_허용값은_소유자의_어휘를_그대로_쓴다")
    void environmentVocabularyIsReused() {
        for (String weather : ShootingEnvironmentVocabulary.WEATHERS) {
            assertThatCode(() -> PortalColumnMetaField.ENV_WEATHER.validate(weather))
                    .doesNotThrowAnyException();
        }
        for (String season : ShootingEnvironmentVocabulary.SEASONS) {
            assertThatCode(() -> PortalColumnMetaField.ENV_SEASON.validate(season))
                    .doesNotThrowAnyException();
        }
        assertThatThrownBy(() -> PortalColumnMetaField.ENV_WEATHER.validate("태풍"))
                .isInstanceOf(CustomException.class);
    }

    // ==================================================== (축, 키) 쌍 식별

    /**
     * ★★ 개인정보 세 키가 <b>두 축에 같은 이름</b>으로 있다. 키만으로 찾으면 영상 값과 프레임 값이
     * 섞이고, 저장 시 영상 축 값이 프레임에 매달린다.
     */
    @Test
    @DisplayName("★같은_키가_두_축에_있고_축까지_주어야_갈린다")
    void sameKeyResolvesDifferentlyPerScope() {
        assertThat(PortalColumnMetaField.of(PortalMetaScope.VIDEO,
                PortalColumnMetaField.Keys.PRIVACY_ANONYMITY))
                .contains(PortalColumnMetaField.VIDEO_ANONYMITY);
        assertThat(PortalColumnMetaField.of(PortalMetaScope.FRAME,
                PortalColumnMetaField.Keys.PRIVACY_ANONYMITY))
                .contains(PortalColumnMetaField.FRAME_ANONYMITY);
        assertThat(PortalColumnMetaField.VIDEO_ANONYMITY.target())
                .isNotEqualTo(PortalColumnMetaField.FRAME_ANONYMITY.target());
    }

    @Test
    @DisplayName("축이_어긋난_쌍은_찾히지_않는다")
    void mismatchedScopeDoesNotResolve() {
        assertThat(PortalColumnMetaField.of(PortalMetaScope.FRAME,
                PortalColumnMetaField.Keys.ENV_WEATHER)).isEmpty();
        assertThat(PortalColumnMetaField.of(PortalMetaScope.VIDEO,
                PortalColumnMetaField.Keys.FRAME_DESCRIPTION)).isEmpty();
    }

    @Test
    @DisplayName("축과_키의_쌍은_전부_유일하다")
    void scopeKeyPairsAreUnique() {
        assertThat(Arrays.stream(PortalColumnMetaField.values())
                .map(f -> f.scope() + "|" + f.metaKey())
                .distinct()
                .count())
                .isEqualTo(PortalColumnMetaField.values().length);
    }

    // ==================================================== 유효값

    @Test
    @DisplayName("수동값이_있으면_사람이_고른_값_없으면_자동값_그것도_없으면_없음이다")
    void effectiveFollowsManualThenDerivedThenNone() {
        LsDataRaw stored = rawWithShotAt(LocalDateTime.of(2026, 1, 1, 12, 0));
        setField(stored, "dayNgtCd", "NGT");

        assertThat(PortalColumnMetaField.ENV_TIME_OF_DAY.effective(stored, null))
                .isEqualTo(new PortalColumnMetaField.Effective("NGT", PortalMetaSource.MANUAL));
        assertThat(PortalColumnMetaField.ENV_SEASON.effective(stored, null))
                .isEqualTo(new PortalColumnMetaField.Effective(
                        TimeOfDaySeasonDeriver.season(LocalDateTime.of(2026, 1, 1, 12, 0)),
                        PortalMetaSource.DERIVED));
        assertThat(PortalColumnMetaField.ENV_WEATHER.effective(stored, null))
                .as("자동 출처가 없는 축은 값을 지어내지 않는다")
                .isEqualTo(new PortalColumnMetaField.Effective(null, PortalMetaSource.NONE));
    }

    @Test
    @DisplayName("빈_값은_지움이라_통과한다")
    void blankValuePassesValidation() {
        assertThatCode(() -> PortalColumnMetaField.ENV_WEATHER.validate(null))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("거부_메시지에_요청받은_값을_되돌려_싣지_않는다")
    void rejectionMessageDoesNotEchoValue() {
        String hostile = "맑음\n2026-01-01 INFO 위조된 로그 줄";
        assertThatThrownBy(() -> PortalColumnMetaField.ENV_WEATHER.validate(hostile))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("위조된 로그 줄"));
    }

    // ==================================================== helpers

    private static boolean accepts(PortalColumnMetaField field, String value) {
        try {
            field.validate(value);
            return true;
        } catch (CustomException e) {
            return false;
        }
    }

    private static LsDataRaw rawWithShotAt(LocalDateTime at) {
        LsDataRaw raw = newInstance(LsDataRaw.class);
        setField(raw, "shtDt", at);
        return raw;
    }

    private static <T> T newInstance(Class<T> type) {
        try {
            var ctor = type.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
