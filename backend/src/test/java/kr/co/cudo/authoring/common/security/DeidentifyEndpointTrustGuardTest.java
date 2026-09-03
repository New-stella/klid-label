package kr.co.cudo.authoring.common.security;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 비식별 엔드포인트 신뢰 가드 테스트 (HIGH-3 / CWE-359·778).
 *
 * <p>배경: 목 서버(mock-server)는 원본을 그대로 복사해 "비식별본"을 만든다(위조 비식별).
 * {@code DEIDENTIFY_MOCK_MODE=false} 로 전환되면서 기존 mock-mode 축의 WARN·prd fail-closed
 * 게이트가 이 경로를 더 이상 덮지 않는다 — {@code kpst.deid.base-url} 만 목 서버로 돌리면
 * stg/prd 에서도 게이트 없이 동일한 위조 비식별이 성립한다.
 *
 * <p>판정 축을 <b>mock-mode 단일 → 신뢰할 수 없는 비식별 엔드포인트</b>로 확장하되,
 * allowlist("이 URL 만 허용")는 채택하지 않는다 — 리포지토리가 stg/prd 실제 KPST 주소를 알 수 없어
 * 정상 운영을 깨뜨리기 때문. <b>알려진 목/시뮬레이터 호스트를 비신뢰로 표시</b>(deny-known-mock)한다.
 */
class DeidentifyEndpointTrustGuardTest {

    private ListAppender<ILoggingEvent> appender;
    private Logger guardLogger;

    @BeforeEach
    void setUp() {
        guardLogger = (Logger) LoggerFactory.getLogger(DeidentifyEndpointTrustGuard.class);
        appender = new ListAppender<>();
        appender.start();
        guardLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        guardLogger.detachAppender(appender);
    }

    @Test
    @DisplayName("비신뢰_비식별_엔드포인트는_경고된다")
    void untrustedEndpointIsWarned() {
        // given: dev — 목 서버 실연동이 목적이므로 차단이 아니라 경고여야 한다
        DeidentifyEndpointTrustGuard guard = guard(
                env(new String[]{"dev"}, null, false), true, "http://klid-mock-server:9400", false);

        // when
        assertThatCode(guard::verify)
                .as("dev 는 목 서버 실연동이 정상 경로다 — 차단하면 dev 파이프라인이 죽는다")
                .doesNotThrowAnyException();

        // then: 어떤 이유로 비신뢰인지 로그로 추적 가능해야 한다
        assertThat(warnMessages())
                .as("비신뢰 비식별 엔드포인트는 WARN 으로 남아야 한다")
                .anySatisfy(msg -> assertThat(msg)
                        .contains("klid-mock-server")
                        .containsIgnoringCase("untrusted"));
    }

    /**
     * ★ 축이 옮겨졌다 — 구 기대값은 <b>"prd 부팅 거부"</b> 였다(2026-09-03 폐기).
     *
     * <p>주소는 <b>배포 설정 한 줄</b>이라 실수 하나로 저작 업무 전체가 멈춘다. 그래서 차단을
     * 없애지 않고 <b>자리를 옮겼다</b>: 기동은 정상이고 <b>비식별로 위탁하려는 순간</b> 거부된다.
     * 위탁이 거부되면 파이프라인이 진행되지 않으므로 <b>위조 비식별본이 산출물·통지로 나가는 것은
     * 그대로 막힌다</b>.
     */
    private void assertBootsButBlocksCommission(DeidentifyEndpointTrustGuard guard, String url) {
        assertThatCode(guard::verify)
                .as("주소 축은 더 이상 기동을 막지 않는다 — %s", url)
                .doesNotThrowAnyException();
        assertThat(guard.commissionBlockReason(url))
                .as("그러나 그 주소로는 위탁하지 않는다 — %s", url)
                .isNotNull();
        assertThat(errorMessages())
                .as("조용히 넘어가지 않는다 — 운영자가 알아야 한다")
                .isNotEmpty();
    }

    @Test
    @DisplayName("★운영에서_비신뢰_비식별_엔드포인트는_기동을_막지_않고_위탁을_막는다")
    void untrustedEndpointBlocksCommissionNotBoot() {
        // given: prd 프로파일 — 위조 비식별본이 학습데이터/외부 통지로 흘러가면 PII 사고
        assertBootsButBlocksCommission(
                guard(env(new String[]{"prd"}, null, true), true,
                        "http://klid-mock-server:9400", false),
                "http://klid-mock-server:9400");
        // and: 프로파일이 아니라 ENV 표식만 운영인 경우도 동일하게 막는다
        assertBootsButBlocksCommission(
                guard(env(new String[]{"dev"}, "prd", false), true,
                        "http://mock-server:9400", false),
                "http://mock-server:9400");
    }

    @Test
    @DisplayName("★운영에서_루프백_비식별_엔드포인트도_기동은_되고_위탁만_막힌다")
    void loopbackEndpointBlocksCommissionNotBoot() {
        for (String url : new String[]{"http://localhost:9201", "https://127.0.0.1:9201"}) {
            // given: 루프백 = 벤더 서버와 시뮬레이터를 구분할 수 없는 주소
            assertBootsButBlocksCommission(
                    guard(env(new String[]{"prd"}, null, true), true, url, false), url);
        }
    }

    @Test
    @DisplayName("★운영이_아니면_위탁을_막지_않는다 — dev_stg_목_연동이_정상_경로다")
    void nonProductionDoesNotBlockCommission() {
        for (String[] profile : new String[][]{{"dev", null}, {"stg", "stg"}, {"local", null}}) {
            DeidentifyEndpointTrustGuard guard = guard(
                    env(new String[]{profile[0]}, profile[1], false), true,
                    "http://klid-mock-server:9400", false);
            assertThat(guard.commissionBlockReason("http://klid-mock-server:9400"))
                    .as("%s 의 목 서버 연동은 정상 경로다", profile[0])
                    .isNull();
        }
    }

    @Test
    @DisplayName("벤더_실서버_엔드포인트는_운영에서도_통과한다")
    void vendorEndpointPassesOnProduction() {
        // given: stg/prd 는 KPST_DEID_BASE_URL 로 실제 벤더 주소를 주입한다
        DeidentifyEndpointTrustGuard guard = guard(
                env(new String[]{"prd"}, "prd", true), true, "https://kpst.vendor.example:9989", false);

        // when / then: allowlist 가 아니라 deny-known-mock 이므로 미지의 벤더 주소는 정상 통과해야 한다
        assertThatCode(guard::verify)
                .as("리포지토리가 모르는 벤더 주소를 막으면 운영 기동이 깨진다")
                .doesNotThrowAnyException();
        assertThat(warnMessages()).isEmpty();
    }

    @Test
    @DisplayName("stg에서_비신뢰_엔드포인트는_경고만_한다")
    void stagingOnlyWarns() {
        // given: 기존 mock-mode 게이트(local/dev/stg 허용, prd 차단)와 동일한 강도
        DeidentifyEndpointTrustGuard guard = guard(
                env(new String[]{"stg"}, "stg", false), true, "http://klid-mock-server:9400", false);

        // when / then
        assertThatCode(guard::verify).doesNotThrowAnyException();
        assertThat(warnMessages()).isNotEmpty();
    }

    @Test
    @DisplayName("mock모드도_동일한_비신뢰_신호로_판정된다")
    void mockModeIsAlsoUntrusted() {
        // given: 자체 복사(self-fill) — 외부 무접촉으로 원본을 비식별본으로 둔갑시킨다
        DeidentifyEndpointTrustGuard dev = guard(
                env(new String[]{"dev"}, null, false), false, "", true);
        DeidentifyEndpointTrustGuard prd = guard(
                env(new String[]{"prd"}, null, true), false, "", true);

        // when / then: 판정은 한 곳에서 두 신호를 모두 본다
        assertThatCode(dev::verify).doesNotThrowAnyException();
        assertThat(warnMessages()).anySatisfy(msg -> assertThat(msg).contains("mock-mode"));
        assertThatThrownBy(prd::verify).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("local에서는_경고없이_통과한다")
    void localIsSilent() {
        // given: local 자족/목 연동은 정상 구성이라 매 기동 경고는 소음이다
        DeidentifyEndpointTrustGuard guard = guard(
                env(new String[]{"local"}, null, false), true, "http://klid-mock-server:9400", true);

        // when / then
        assertThatCode(guard::verify).doesNotThrowAnyException();
        assertThat(warnMessages()).isEmpty();
    }

    @Test
    @DisplayName("비식별_위탁이_비활성이면_판정하지_않는다")
    void disabledDeidentifyPathIsNotJudged() {
        // given: kpst 비활성 + mock 아님 (엔드포인트 자체가 없음)
        DeidentifyEndpointTrustGuard guard = guard(
                env(new String[]{"prd"}, null, true), false, "http://klid-mock-server:9400", false);

        // when / then
        assertThatCode(guard::verify).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("언더스코어_호스트의_벤더주소는_운영에서도_부팅을_막지_않는다")
    void underscoreVendorHostDoesNotBlockBoot() {
        // given: 도커 컴포즈 서비스명 등 언더스코어 호스트는 URI#getHost() 가 null 을 준다.
        //        이를 "판정 불가 → 비신뢰"로 떨구면 prd 에서 앱 전체가 부팅 거부된다.
        for (String url : new String[]{"http://kpst_deid:9201", "http://kpst_user@kpst_deid:9201"}) {
            DeidentifyEndpointTrustGuard guard = guard(
                    env(new String[]{"prd"}, "prd", true), true, url, false);

            // when / then
            assertThatCode(guard::verify)
                    .as("언더스코어 호스트(%s)는 목/루프백이 아니므로 운영 기동을 깨뜨리면 안 된다", url)
                    .doesNotThrowAnyException();
        }
        assertThat(warnMessages()).isEmpty();
    }

    @Test
    @DisplayName("언더스코어_호스트라도_목이면_운영에서_여전히_차단된다")
    void underscoreMockHostIsStillBlocked() {
        // given: authority 폴백이 차단 강도를 낮추면 안 된다 — 폴백으로 뽑은 호스트도 같은 deny 판정을 탄다
        for (String url : new String[]{"http://klid_mock_server:9400", "http://kpst_user@mock_server:9400"}) {
            DeidentifyEndpointTrustGuard guard = guard(
                    env(new String[]{"prd"}, null, true), true, url, false);

            // when / then — 판정은 그대로이고, 막는 자리만 기동 → 위탁으로 옮겼다.
            assertBootsButBlocksCommission(guard, url);
        }
    }

    @Test
    @DisplayName("IPv6_루프백_엔드포인트는_운영에서_차단된다")
    void ipv6LoopbackIsBlockedOnProduction() {
        // given: URI#getHost() 는 IPv6 를 대괄호 포함([::1])으로 준다 — 정규화가 없으면 deny 항목이 사문화된다
        DeidentifyEndpointTrustGuard guard = guard(
                env(new String[]{"prd"}, null, true), true, "http://[::1]:9201", false);

        // when / then
        assertBootsButBlocksCommission(guard, "http://[::1]:9201");
    }

    @Test
    @DisplayName("호스트_파싱_불가시_사유힌트가_남는다")
    void unparsableHostLeavesReasonHint() {
        // given: 운영자가 원인을 즉시 알 수 있어야 한다(전체 URL 평문은 남기지 않는다)
        DeidentifyEndpointTrustGuard guard = guard(
                env(new String[]{"dev"}, null, false), true, "kpst_deid:9201/path", false);

        // when
        assertThatCode(guard::verify).doesNotThrowAnyException();

        // then
        assertThat(warnMessages())
                .anySatisfy(msg -> assertThat(msg)
                        .contains("호스트 파싱 불가")
                        .doesNotContain("/path"));
    }

    @Test
    @DisplayName("해석불가한_base_url은_비신뢰로_간주한다")
    void unparsableBaseUrlIsUntrusted() {
        // given: 호스트를 확인할 수 없는 값 → 판정 불가 → 안전한 쪽(fail-secure)
        DeidentifyEndpointTrustGuard guard = guard(
                env(new String[]{"prd"}, null, true), true, "  ", false);

        // when / then — 값 미설정도 "아직 안 정해짐" 이라 기동을 막지 않는다. 위탁은 막힌다.
        assertBootsButBlocksCommission(guard, "  ");
    }

    @Test
    @DisplayName("★자체복사_mock_mode_는_그대로_운영_기동을_막는다 — 주소가_아니라_개발_토글이다")
    void mockModeStillBlocksProductionBoot() {
        // 이번 반전의 대상은 <외부 연동 주소>다. 개발 편의 토글의 운영 노출 차단은 별개 축이며
        //   구속 정책이다 — 함께 걷어내지 말 것.
        DeidentifyEndpointTrustGuard prd = guard(
                env(new String[]{"prd"}, null, true), true, "https://kpst.vendor.example:9989", true);
        assertThatThrownBy(prd::verify)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mock-mode");
    }

    private DeidentifyEndpointTrustGuard guard(Environment environment,
                                               boolean kpstEnabled, String baseUrl, boolean mockMode) {
        DeidentifyEndpointTrustGuard guard = new DeidentifyEndpointTrustGuard(environment);
        ReflectionTestUtils.setField(guard, "kpstEnabled", kpstEnabled);
        ReflectionTestUtils.setField(guard, "kpstBaseUrl", baseUrl);
        ReflectionTestUtils.setField(guard, "mockMode", mockMode);
        return guard;
    }

    /** activeProfiles + ENV + prd 프로파일 활성 여부를 갖는 Environment mock. */
    private Environment env(String[] activeProfiles, String envName, boolean acceptsPrd) {
        Environment environment = mock(Environment.class);
        when(environment.getActiveProfiles()).thenReturn(activeProfiles);
        when(environment.getProperty("ENV")).thenReturn(envName);
        when(environment.acceptsProfiles(any(Profiles.class))).thenReturn(acceptsPrd);
        return environment;
    }

    private java.util.List<String> errorMessages() {
        return appender.list.stream()
                .filter(e -> e.getLevel() == Level.ERROR)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    private java.util.List<String> warnMessages() {
        return appender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }
}
