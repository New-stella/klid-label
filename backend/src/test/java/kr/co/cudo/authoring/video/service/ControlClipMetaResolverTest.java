package kr.co.cudo.authoring.video.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.entity.MngClipEvntLst;
import kr.co.cudo.authoring.video.service.ControlClipMetaResolver.ShootingEnv;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 관제 이벤트리스트 코드값 해석기({@link ControlClipMetaResolver}) 단위 테스트.
 *
 * <p>채택 대상은 <b>시간대({@code HR_TYPE_CD}) · 계절({@code SESN_CD}) · 개인정보유형
 * ({@code PRVC_TYPE_CD}) 3개</b>다. 날씨({@code WTHR_CD})는 관제에서 받지 않는다
 * (2026-07-31 사용자 확정 — 저작도구 수동 입력이 유일한 원천).
 *
 * <p>3분기를 검증한다:
 * <ol>
 *   <li><b>채택</b> — 관제값이 저작도구 허용 어휘에 있으면 그대로 채택</li>
 *   <li><b>폴백 + WARN</b> — 관제값이 있으나 허용 어휘 미매칭이면 채택하지 않고 WARN 으로 드러냄
 *       (촬영환경은 미상 유지, 개인정보유형은 fail-closed 기본값 {@code PRVC})</li>
 *   <li><b>기존 폴백</b> — 관제값이 없으면(null/blank) WARN 없이 촬영환경 null · 개인정보유형 {@code PRVC}</li>
 * </ol>
 */
class ControlClipMetaResolverTest {

    private ControlClipMetaResolver resolver;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() {
        resolver = new ControlClipMetaResolver();
        logAppender = new ListAppender<>();
        logAppender.start();
        logger().addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        logger().detachAppender(logAppender);
        logAppender.stop();
    }

    private static Logger logger() {
        return (Logger) LoggerFactory.getLogger(ControlClipMetaResolver.class);
    }

    private List<String> warnMessages() {
        return logAppender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    /** 관제 이벤트리스트 1행 생성 (읽기 전용 엔티티 — 리플렉션 세팅). */
    private static MngClipEvntLst evntLst(String wthrCd, String hrTypeCd, String sesnCd, String prvcTypeCd) {
        MngClipEvntLst e;
        try {
            var ctor = MngClipEvntLst.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            e = ctor.newInstance();
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
        ReflectionTestUtils.setField(e, "evntId", "EVT-1");
        ReflectionTestUtils.setField(e, "evntTypeCd", "FIRE");
        ReflectionTestUtils.setField(e, "wthrCd", wthrCd);
        ReflectionTestUtils.setField(e, "hrTypeCd", hrTypeCd);
        ReflectionTestUtils.setField(e, "sesnCd", sesnCd);
        ReflectionTestUtils.setField(e, "prvcTypeCd", prvcTypeCd);
        return e;
    }

    // ── ① 채택 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("시간대와_계절은_여전히_채택된다")
    void adoptsControlShootingEnvironmentWhenInVocabulary() {
        // given — 관제 시간대·계절이 저작도구 허용 어휘와 일치(회귀 가드).
        MngClipEvntLst evntLst = evntLst(null, "DAY", "SUMMER", null);

        // when
        ShootingEnv env = resolver.resolve(evntLst);

        // then — 관제값 그대로 채택.
        assertThat(env.dayNgtCd()).isEqualTo("DAY");
        assertThat(env.sesnCd()).isEqualTo("SUMMER");
        assertThat(warnMessages()).isEmpty();
    }

    @Test
    @DisplayName("관제_촬영환경값의_앞뒤_공백은_제거하고_채택한다")
    void trimsAdoptedValues() {
        // given
        MngClipEvntLst evntLst = evntLst(null, " NGT ", " WINTER ", null);

        // when
        ShootingEnv env = resolver.resolve(evntLst);

        // then
        assertThat(env.dayNgtCd()).isEqualTo("NGT");
        assertThat(env.sesnCd()).isEqualTo("WINTER");
    }

    @Test
    @DisplayName("촬영환경_필드별로_독립_판정한다")
    void resolvesEachFieldIndependently() {
        // given — 시간대만 허용값, 계절은 관제 코드값(미매칭).
        MngClipEvntLst evntLst = evntLst(null, "DAY", "S1", null);

        // when
        ShootingEnv env = resolver.resolve(evntLst);

        // then — 매칭된 시간대만 채택되고 나머지는 미상(null).
        assertThat(env.dayNgtCd()).isEqualTo("DAY");
        assertThat(env.sesnCd()).isNull();
    }

    // ── 날씨: 관제 미수신 (2026-07-31 사용자 확정) ─────────────────────────

    @Test
    @DisplayName("날씨는_관제값이_있어도_채택하지_않는다")
    void neverAdoptsControlWeather() {
        // given — 관제 코드값(CLEAR)이든 저작도구 표시명(맑음)이든 모두 무시한다.
        //         날씨는 관제에서 받지 않고 저작도구에서 직접 입력한다.
        ShootingEnv fromCode = resolver.resolve(evntLst("CLEAR", "DAY", "SUMMER", null));
        ShootingEnv fromDisplayName = resolver.resolve(evntLst("맑음", "DAY", "SUMMER", null));

        // then — 촬영환경 채택 결과에 날씨 자리가 없다(시간대·계절만).
        assertThat(fromCode.dayNgtCd()).isEqualTo("DAY");
        assertThat(fromDisplayName.dayNgtCd()).isEqualTo("DAY");
        assertThat(ShootingEnv.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("dayNgtCd", "sesnCd");
    }

    @Test
    @DisplayName("날씨_미채택은_WARN을_남기지_않는다")
    void doesNotWarnForControlWeather() {
        // given — 관제 날씨 코드값은 아예 보지 않으므로 "미매칭 WARN" 대상도 아니다
        //         (적재 1건당 1 WARN 이 영구히 쌓이던 소음 제거).
        resolver.resolve(evntLst("CLEAR", "DAY", "SUMMER", null));

        // then
        assertThat(warnMessages()).isEmpty();
    }

    // ── ② 폴백 + WARN ──────────────────────────────────────────────────────

    @Test
    @DisplayName("관제값이_허용어휘에_없으면_폴백하고_WARN_을_남긴다")
    void fallsBackWithWarnWhenNotInVocabulary() {
        // given — 관제 코드값↔저작도구 코드도메인 매핑표 미확정 상태에서 흔한 형태(숫자 코드).
        MngClipEvntLst evntLst = evntLst(null, "02", "03", null);

        // when
        ShootingEnv env = resolver.resolve(evntLst);

        // then — 미매칭 값은 채택하지 않고(미상 유지) WARN 2건으로 드러낸다.
        assertThat(env.dayNgtCd()).isNull();
        assertThat(env.sesnCd()).isNull();
        assertThat(warnMessages()).hasSize(2);
        assertThat(warnMessages()).allSatisfy(m -> assertThat(m).contains("[ControlClipMeta]"));
    }

    @Test
    @DisplayName("미매칭_관제코드값은_반환값에_그대로_실리지_않는다")
    void neverLeaksUnmatchedCodeIntoResult() {
        // given — export/스냅샷까지 흘러가면 안 되는 미검증 문자열.
        MngClipEvntLst evntLst = evntLst("<script>alert(1)</script>", "DAYTIME", "여름철", null);

        // when
        ShootingEnv env = resolver.resolve(evntLst);

        // then
        assertThat(env.dayNgtCd()).isNull();
        assertThat(env.sesnCd()).isNull();
    }

    // ── ③ 기존 폴백 (관제값 없음) ─────────────────────────────────────────

    @Test
    @DisplayName("관제_촬영환경값이_없으면_촬영일시_파생값으로_폴백된다")
    void keepsUnknownWhenControlValueAbsent() {
        // given — 관제 3필드 모두 null(현재 dev/stg/prd 실데이터 형태).
        MngClipEvntLst evntLst = evntLst(null, null, null, null);

        // when
        ShootingEnv env = resolver.resolve(evntLst);

        // then — 적재는 미상(null) 유지. 시간대·계절 파생 폴백은 기존대로 조회 시점
        //        (EnvironmentMetaService + TimeOfDaySeasonDeriver)이 담당한다 — 추정값을
        //        LS_DATA_RAW 에 영속하면 수동입력(MANUAL)으로 승격돼 동결·export 로 샌다(E-ISSUE-42).
        assertThat(env.dayNgtCd()).isNull();
        assertThat(env.sesnCd()).isNull();
        assertThat(warnMessages()).isEmpty();
    }

    @Test
    @DisplayName("관제_촬영환경값이_공백이면_값없음으로_처리한다")
    void treatsBlankAsAbsent() {
        // given
        MngClipEvntLst evntLst = evntLst("  ", "", "\t", null);

        // when
        ShootingEnv env = resolver.resolve(evntLst);

        // then — 공백은 "값 없음"이라 WARN 대상이 아니다.
        assertThat(env.dayNgtCd()).isNull();
        assertThat(env.sesnCd()).isNull();
        assertThat(warnMessages()).isEmpty();
    }

    @Test
    @DisplayName("이벤트리스트가_null이어도_해석이_안전하다")
    void resolvesSafelyWhenEvntLstNull() {
        // when — 이벤트리스트 미매칭은 적재를 막지 않는다(기존 계약).
        ShootingEnv env = resolver.resolve(null);

        // then — 촬영환경은 미상, 개인정보유형은 fail-closed 기본값(PRVC).
        assertThat(env.dayNgtCd()).isNull();
        assertThat(env.sesnCd()).isNull();
        assertThat(resolver.resolvePrvcType(null)).isEqualTo(LsDataRaw.PRVC_TYPE_PRVC);
        assertThat(warnMessages()).isEmpty();
    }

    // ── 개인정보 유형 ───────────────────────────────────────────────────────

    @Test
    @DisplayName("관제_개인정보유형이_ANONY면_그대로_적재된다")
    void adoptsControlPrvcType() {
        // 입력값 존중 회귀 가드 — 관제(또는 관제를 거치지 않는 경로)가 값을 주면 그 값이 이긴다.
        assertThat(resolver.resolvePrvcType(evntLst(null, null, null, "ANONY")))
                .isEqualTo(LsDataRaw.PRVC_TYPE_ANONY);
        assertThat(resolver.resolvePrvcType(evntLst(null, null, null, " ANONY ")))
                .isEqualTo(LsDataRaw.PRVC_TYPE_ANONY);
        assertThat(resolver.resolvePrvcType(evntLst(null, null, null, "PRVC")))
                .isEqualTo(LsDataRaw.PRVC_TYPE_PRVC);
        assertThat(resolver.resolvePrvcType(evntLst(null, null, null, "PSDO")))
                .isEqualTo(LsDataRaw.PRVC_TYPE_PSDO);
        assertThat(warnMessages()).isEmpty();
    }

    @Test
    @DisplayName("관제_개인정보유형이_없으면_PRVC로_폴백된다")
    void fallsBackToPrvcWhenPrvcTypeAbsent() {
        // 관제는 개인정보유형을 채워 보내지 않는다(2026-07-31 확인) — 입력이 없으면
        // "개인정보가 있고 익명처리되지 않은 원천영상"으로 본다(fail-closed). WARN 은 남기지 않는다.
        assertThat(resolver.resolvePrvcType(evntLst(null, null, null, null)))
                .isEqualTo(LsDataRaw.PRVC_TYPE_PRVC);
        assertThat(resolver.resolvePrvcType(evntLst(null, null, null, "   ")))
                .isEqualTo(LsDataRaw.PRVC_TYPE_PRVC);
        assertThat(warnMessages()).isEmpty();
    }

    @Test
    @DisplayName("관제_개인정보유형이_알수없는값이면_PRVC로_폴백하고_WARN을_남긴다")
    void fallsBackToPrvcWithWarnWhenPrvcTypeUnknown() {
        // given/when — UNKNOWN 은 적재 채택 대상이 아니다(마이그레이션 잠정값).
        String resolved = resolver.resolvePrvcType(evntLst(null, null, null, "UNKNOWN"));

        // then — 미검증 코드값은 채택하지 않고 fail-closed 기본값으로 떨어진다.
        assertThat(resolved).isEqualTo(LsDataRaw.PRVC_TYPE_PRVC);
        assertThat(warnMessages()).hasSize(1);
        assertThat(warnMessages().get(0)).contains("[ControlClipMeta]");
    }

    // ── 보안: Log Injection (CWE-117) ──────────────────────────────────────

    @Test
    @DisplayName("관제_코드값에_개행이_있어도_로그가_오염되지_않는다")
    void sanitizesControlCodeInWarnLog() {
        // given — 관제 DB 값은 신뢰 경계 밖. 개행이 섞이면 가짜 로그 라인을 위조할 수 있다.
        String forged = "01\n2026-07-31 00:00:00 ERROR [ControlClipMeta] forged-line";
        MngClipEvntLst evntLst = evntLst(null, forged, null, "PRVC\r\nforged");

        // when
        resolver.resolve(evntLst);
        resolver.resolvePrvcType(evntLst);

        // then — WARN 메시지에 개행/캐리지리턴이 남지 않는다.
        assertThat(warnMessages()).isNotEmpty();
        assertThat(warnMessages()).allSatisfy(m -> {
            assertThat(m).doesNotContain("\n");
            assertThat(m).doesNotContain("\r");
        });
    }

    @Test
    @DisplayName("관제_코드값이_과도하게_길어도_로그를_잘라낸다")
    void truncatesOverlongControlCodeInWarnLog() {
        // given — 비정상 입력으로 인한 로그 폭주 방지.
        String overlong = "X".repeat(5000);
        MngClipEvntLst evntLst = evntLst(null, overlong, null, null);

        // when
        resolver.resolve(evntLst);

        // then
        assertThat(warnMessages()).hasSize(1);
        assertThat(warnMessages().get(0).length()).isLessThan(1000);
    }
}
