package kr.co.cudo.authoring.common.security;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import kr.co.cudo.authoring.common.client.NonRetryableExternalException;
import kr.co.cudo.authoring.common.config.ExternalEndpointAddress;
import kr.co.cudo.authoring.common.config.KpstWebClientConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

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
        // 운영은 더 이상 기동을 막지 않는다(ADR-062) — 대신 ERROR 로 남고 산출·위탁이 막힌다.
        assertThatCode(prd::verify).doesNotThrowAnyException();
        assertThat(errorMessages()).anySatisfy(msg -> assertThat(msg).contains("mock-mode"));
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

    /**
     * ★ 축이 옮겨졌다 — 구 기대값은 <b>"자체복사 mock-mode 는 그대로 운영 기동을 막는다"</b>
     * 였다(2026-09-03 폐기 · {@code ADR-062}).
     *
     * <p>그것도 결국 <b>배포 설정 한 줄</b>이라, 온프렘에서는 「앱이 안 뜬다」가 「비식별만 안 된다」
     * 보다 큰 대가다. <b>막는 것과 그 강도는 그대로이고 자리만 옮겼다</b> — 기동은 되고
     * <b>비식별 산출·위탁이 전건 거부</b>된다. 되돌리지 말 것.
     */
    @Test
    @DisplayName("★자체복사_mock_mode_는_기동을_막지_않고_비식별_산출을_막는다")
    void mockModeBlocksCommissionNotBoot() {
        // given: 벤더 실주소라 <주소 축은 멀쩡한> 형상 — 그래도 자체 복사면 막혀야 한다.
        String vendorUrl = "https://kpst.vendor.example:9989";
        DeidentifyEndpointTrustGuard prd = guard(
                env(new String[]{"prd"}, null, true), true, vendorUrl, true);

        // when / then
        assertThatCode(prd::verify)
                .as("설정 한 줄로 저작 업무 전체가 멈추지 않는다")
                .doesNotThrowAnyException();
        assertThat(prd.commissionBlockReason(vendorUrl))
                .as("주소가 멀쩡해도 자체 복사면 위조 비식별본이 나갈 수 있다 — 산출을 막아야 한다")
                .isEqualTo(DeidentifyEndpointTrustGuard.MOCK_MODE_REASON);
        assertThat(prd.selfCopyBlocked())
                .as("상태 창구(헬스)가 UP 으로 가리지 않도록 같은 사실을 노출한다")
                .isTrue();
        assertThat(errorMessages())
                .as("조용한 실패 금지 — 비식별이 전건 실패한다는 사실이 기동 로그에 남아야 한다")
                .anySatisfy(msg -> assertThat(msg).contains("mock-mode"));
    }

    @Test
    @DisplayName("★비운영에서는_자체복사가_산출을_막지_않는다 — local_dev_stg_동작_불변")
    void mockModeDoesNotBlockCommissionOutsideProduction() {
        for (String[] profile : new String[][]{{"local", null}, {"dev", null}, {"stg", "stg"}}) {
            DeidentifyEndpointTrustGuard guard = guard(
                    env(new String[]{profile[0]}, profile[1], false), true,
                    "http://klid-mock-server:9400", true);
            assertThat(guard.commissionBlockReason("http://klid-mock-server:9400"))
                    .as("%s 의 자체 복사는 종전대로 허용된다", profile[0])
                    .isNull();
            assertThat(guard.selfCopyBlocked())
                    .as("%s 는 운영이 아니므로 산출이 막히지 않는다", profile[0])
                    .isFalse();
        }
    }

    /**
     * ★★ <b>거부가 실제로 전송을 막는가</b> — 사슬 전체를 잇는 유일한 시험.
     *
     * <h3>왜 이 시험이 필요한가 (독립 QA 실측)</h3>
     * <p>이 파일의 다른 시험들은 {@link DeidentifyEndpointTrustGuard#commissionBlockReason(String)}
     * 의 <b>반환값</b>만 본다. 그래서 <b>그 반환값을 아무도 쓰지 않게 배선을 통째로 지워도 죽는 시험이
     * 0건</b>이었다 — 가드는 초록인데 전송은 그대로 나가는 상태를 아무도 잡지 못한다. 자체 복사 축뿐
     * 아니라 직전 라운드의 주소 축도 같은 갭이었다.
     *
     * <h3>무엇을 잇는가</h3>
     * <ol>
     *   <li>가드 판정 → {@code KpstWebClientConfig.kpstDeidEndpointAddress} 가 주소를
     *       <b>쓸 수 없는 상태</b>로 낮춘다.</li>
     *   <li>그 주소로 만든 클라이언트가 <b>소켓을 열지 않고</b> 전송 차단 예외로 끝난다.</li>
     * </ol>
     *
     * <p>주소는 <b>운영 확정값(사설 IP)</b>을 쓴다 — 주소 정책을 통과하는 값이라야 "자체 복사 축이
     * 막았다"가 성립한다. 목/예시 호스트를 쓰면 주소 축이 먼저 걸려 <b>이 시험이 무의미</b>해진다
     * ({@code ExternalEndpointAddress#rejectedBecause} 는 이미 거부된 주소에 덧입히지 않는다).
     */
    @Test
    @DisplayName("★★자체복사_거부가_주소빈을_거쳐_실제_전송까지_막는다 — 반환값이_아니라_사슬을_본다")
    void selfCopyRejectionActuallyBlocksTransport() {
        // given — 운영 + mock-mode 인 <실제> 가드(목 객체 아님). 주소는 정책을 통과하는 확정값.
        String vendorUrl = "http://10.177.33.162:9989";
        DeidentifyEndpointTrustGuard prd = guard(
                env(new String[]{"prd"}, null, true), true, vendorUrl, true);
        KpstWebClientConfig cfg = new KpstWebClientConfig();

        // when — 운영 배선 그대로 주소 빈을 만든다.
        ExternalEndpointAddress address = cfg.kpstDeidEndpointAddress(vendorUrl, "", prd);

        // then ① 사슬 1단 — 주소가 쓸 수 없는 상태로 낮춰진다.
        assertThat(address.usable())
                .as("가드가 막았다고 말했는데 주소가 그대로면 사슬이 여기서 끊긴다")
                .isFalse();
        assertThat(address.baseUrl())
                .as("거부된 주소는 빈 문자열로 낮춰져야 한다 — 그래야 나쁜 주소로 실제로 나가지 않는다")
                .isEmpty();

        // then ② 사슬 2단 — 그 주소로 만든 클라이언트가 전송을 시도조차 하지 않는다.
        WebClient client = cfg.kpstDeidWebClient(address, "", null);
        assertThatThrownBy(() -> client.post().uri("/create_project").bodyValue("{}")
                .retrieve().bodyToMono(String.class).block(Duration.ofSeconds(5)))
                .as("연결 거부(ConnectException)가 나면 이미 전송을 시도했다는 뜻이다")
                .isInstanceOf(NonRetryableExternalException.class);
    }

    @Test
    @DisplayName("★저장시점_판정은_이번_반전의_대상이_아니다 — 운영에서_목주소_저장은_여전히_거부")
    void saveTimeGuardIsUnchanged() {
        DeidentifyEndpointTrustGuard prd = guard(
                env(new String[]{"prd"}, null, true), true, "https://kpst.vendor.example:9989", false);
        assertThatThrownBy(() -> prd.verifyForSave("http://klid-mock-server:9400"))
                .isInstanceOf(kr.co.cudo.authoring.common.exception.CustomException.class);
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
