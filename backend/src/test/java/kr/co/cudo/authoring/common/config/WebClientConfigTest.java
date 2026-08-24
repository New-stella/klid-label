package kr.co.cudo.authoring.common.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import kr.co.cudo.authoring.sysconfig.endpoint.IntegrationEndpointResolver;
import kr.co.cudo.authoring.sysconfig.service.SystemConfigService;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * WebClientConfig 의 VLM URL 검증(SSRF / HTTPS / placeholder fail-closed) 단위 테스트.
 *
 * <p>대상: {@code WebClientConfig#vlmWebClient}
 *  - baseUrl 이 주입돼 있을 때 검증 적용 (정책 판정은 {@link VlmUrlPolicy} 단일 원천)
 *  - baseUrl 이 비어 있을 때 검증 생략 (미연동 환경 기동 보장 — ADR-049)
 *
 * <p>구 기준(설정 토글 {@code enabled} 의 참/거짓)은 그 토글이 폐지되면서 사라졌다. 판정 축이
 * <b>토글 → 주소 주입 여부</b>로 옮겨간 것이며, 아래 기대값들은 그 축으로 다시 쓰였다.
 * 미연동 기동 보장 자체의 전용 가드는 {@link VlmBlankUrlBootTest} 다.
 *
 * <p>프로파일별 완화/엄격 분리 자체의 검증은 {@link VlmUrlPolicyTest} 가 담당한다. 여기서는
 * <b>빈 생성 경로가 그 정책을 실제로 경유하는지</b>를 고정한다.
 */
class WebClientConfigTest {

    private final WebClientConfig cfg = new WebClientConfig();

    /** 운영 등가(엄격) 정책 — 완화 플래그 off. */
    private static VlmUrlPolicy strictPolicy() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prd");
        return new VlmUrlPolicy(env, false);
    }

    /** local 목업 등가(완화) 정책 — 평문 http + 사설 IP 허용. */
    private static VlmUrlPolicy relaxedPolicy() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("local");
        return new VlmUrlPolicy(env, true);
    }

    /**
     * ★ 뒤집힌 단언이다 — 구 기대값은 "설정 토글이 꺼져 있으면 어떤 URL 이든 통과" 였다.
     *
     * <p>그 토글은 폐지됐고(ADR-049), 검증을 생략하는 조건은 이제 <b>주소가 비어 있는 것</b> 하나다.
     * 따라서 {@code http://localhost:9400} 같은 <b>채워진</b> 주소는 더 이상 면제 대상이 아니다 —
     * 아래 {@code localhostRejected} 가 그 주소를 거부 대상으로 고정한다.
     */
    @Test
    @DisplayName("WebClientConfig_vlm_url_이_비어있으면_검증_생략_미연동_기동_보장")
    void blankUrlSkipsValidation() {
        // given / when / then — 주소 미주입이면 어떤 정책에서도 빈 생성 성공(기동 차단 금지)
        assertThat(cfg.vlmWebClient("", "", strictPolicy(), null)).isNotNull();
        assertThat(cfg.vlmWebClient("  ", "", strictPolicy(), null)).isNotNull();
    }

    @Test
    @DisplayName("WebClientConfig_vlm_url_http_시_빈_생성_실패_HTTPS_강제")
    void httpSchemaRejectedWhenEnabled() {
        assertThatThrownBy(() -> cfg.vlmWebClient("http://vlm.vendor.io", "", strictPolicy(), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HTTPS");
    }

    @Test
    @DisplayName("WebClientConfig_vlm_url_localhost_면_빈_생성_실패_SSRF_차단")
    void localhostRejectedWhenEnabled() {
        assertThatThrownBy(() -> cfg.vlmWebClient("https://localhost:9400", "", strictPolicy(), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("내부");
    }

    @Test
    @DisplayName("WebClientConfig_vlm_url_private_IP_시_빈_생성_실패_SSRF_차단")
    void privateIpRejectedWhenEnabled() {
        assertThatThrownBy(() -> cfg.vlmWebClient("https://10.0.0.5", "", strictPolicy(), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("내부");
        assertThatThrownBy(() -> cfg.vlmWebClient("https://192.168.1.10", "", strictPolicy(), null))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cfg.vlmWebClient("https://172.16.0.1", "", strictPolicy(), null))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cfg.vlmWebClient("https://169.254.169.254", "", strictPolicy(), null))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cfg.vlmWebClient("https://127.0.0.1", "", strictPolicy(), null))
                .isInstanceOf(IllegalStateException.class);
    }

    /**
     * ★ 빈 문자열 케이스가 <b>여기서 빠졌다</b> — 폐기가 아니라 {@link #blankUrlSkipsValidation} 으로
     * <b>기대값이 뒤집혀 이동</b>했다. 빈 값은 이제 "설정 안 함(미연동)" 이지 placeholder 가 아니다.
     * 여기 남은 것은 <b>사람이 예시 값을 그대로 배포한</b> 형태(fail-closed 대상)뿐이다.
     */
    @Test
    @DisplayName("WebClientConfig_vlm_url_placeholder_시_빈_생성_실패_fail_closed")
    void placeholderRejectedWhenEnabled() {
        assertThatThrownBy(() -> cfg.vlmWebClient("https://example.com", "", strictPolicy(), null))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cfg.vlmWebClient("https://your-vlm-service", "", strictPolicy(), null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("WebClientConfig_vlm_url_정상_HTTPS_공인_IP_시_빈_생성_성공")
    void validHttpsPublicIpAccepted() {
        // 공인 IP(8.8.8.8 — Google DNS) 직접 사용해 DNS 의존성 없이 검증 성공 케이스만 확인.
        assertThat(cfg.vlmWebClient("https://8.8.8.8/", "", strictPolicy(), null)).isNotNull();
    }

    @Test
    @DisplayName("WebClientConfig_vlm_local_완화정책_시_평문_HTTP_사설IP_목업_URL_빈_생성_성공")
    void relaxedPolicyAcceptsPlaintextMockUrl() {
        assertThat(cfg.vlmWebClient("http://klid-mock-server:9400", "", relaxedPolicy(), null)).isNotNull();
        assertThat(cfg.vlmWebClient("http://127.0.0.1:9400", "", relaxedPolicy(), null)).isNotNull();
    }

    @Test
    @DisplayName("WebClientConfig_vlm_평문구간에_토큰이_설정되면_경고하고_토큰값은_출력하지_않는다")
    void cleartextTokenLogsWarning() {
        // given: local/dev 완화 경로는 TLS 가 없어 Bearer 토큰이 평문으로 흐른다(CWE-319).
        //   경고는 공용 골격(ProfileGatedUrlPolicy)이 남기므로 로거도 그 클래스다.
        Logger logger = (Logger) LoggerFactory.getLogger(ProfileGatedUrlPolicy.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            // when
            assertThat(cfg.vlmWebClient("http://klid-mock-server:9400", "secret-token-value",
                    relaxedPolicy(), null)).isNotNull();

            // then: 경고가 남되 토큰 값은 절대 출력되지 않는다(길이만 — CWE-532)
            List<String> warns = appender.list.stream()
                    .filter(e -> e.getLevel() == Level.WARN)
                    .map(ILoggingEvent::getFormattedMessage)
                    .toList();
            assertThat(warns).anyMatch(m -> m.contains("평문") && m.contains("토큰"));
            assertThat(warns).anyMatch(m -> m.contains("tokenLength=18"));
            assertThat(warns).noneMatch(m -> m.contains("secret-token-value"));

            // and: HTTPS 구간에서는 경고하지 않는다
            appender.list.clear();
            assertThat(cfg.vlmWebClient("https://8.8.8.8/", "secret-token-value", strictPolicy(), null))
                    .isNotNull();
            assertThat(appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(m -> m.contains("tokenLength")))
                    .isEmpty();
        } finally {
            logger.detachAppender(appender);
        }
    }

    /**
     * 설정 override 를 돌려주는 리졸버 — 실제 조회 경로({@code SystemConfigService} → 리졸버) 그대로다.
     * 판정을 통과시키기 위한 스텁이 아니라 "설정에 값이 들어 있는 상태"의 재현이다.
     */
    private static IntegrationEndpointResolver resolverReturning(String overrideUrl) {
        SystemConfigService configService = mock(SystemConfigService.class);
        when(configService.findString(anyString())).thenReturn(java.util.Optional.ofNullable(overrideUrl));
        @SuppressWarnings("unchecked")
        ObjectProvider<SystemConfigService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(configService);
        return new IntegrationEndpointResolver(provider);
    }

    /**
     * ★ <b>확정된 동작을 고정하는 회귀 가드 — 결함이 아니다. 되돌리지 말 것</b> (2026-08-10 판단).
     *
     * <h3>무엇이 확정됐나</h3>
     * <p>{@link VlmUrlPolicy} 의 검증(운영 엄격 = HTTPS 전용 + 내부/사설 대역 차단)은
     * {@code vlmWebClient} 의 <b>{@code @Value} 배포 기본값에만</b> 걸린다. 운영 화면에서 넣는
     * <b>설정 override 는 그 검증을 거치지 않는다</b> — override 에 남는 검증은
     * {@code IntegrationEndpointUrlValidator} 의 <b>스킴(http/https) + 형식</b>뿐이다.
     *
     * <h3>왜 그것이 의도인가</h3>
     * <p>온프렘 내부망 배포이고 연동 4종(비식별·AI 추론·시계열 분석·관제 통지)이 <b>내부 서버일 가능성이
     * 높아</b>, override 에 HTTPS·대역 제한을 강제하면 <b>정당한 대상을 막는다</b>. 같은 이유로 대역 차단이
     * 이미 폐지됐다({@code IntegrationEndpointUrlValidator} 참조).
     *
     * <h3>왜 문서가 아니라 테스트로 고정하나</h3>
     * <p>문서에만 있으면 다음 사람이 <b>기동 시점 정책을 불변식으로 읽는다</b> — "선언은 막는다는데 실제로는
     * 안 막는" 형태가 이 저장소의 반복 결함이다. 여기서는 반대로 <b>안 막는다는 사실 자체</b>를 실소켓으로
     * 고정한다. 반대로 override 에 정책을 걸기로 <b>결정을 뒤집는다면</b> 이 테스트가 실패하므로, 그때는
     * 지우는 게 아니라 <b>기대값을 뒤집고 사유를 남긴다</b>.
     *
     * <p><b>mutation 확인</b>: {@code WebClientConfig#vlmWebClient} 에서 필터 배선
     * ({@code .filter(IntegrationEndpointExchangeFilter.of(...))})을 지우면 요청이 override 주소로 나가지
     * 않아 이 테스트가 실패한다.
     *
     * <p><b>운영(stg/prd) 상당 조건</b>: 정책 인스턴스를 {@code prd} 프로파일 + 완화 플래그 off
     * ({@link #strictPolicy()})로 만들어 실제로 엄격 판정이 걸리는 상태에서 검증한다. 다만 그 조건에서는
     * 배포 기본값이 <b>https + 공인 대역</b>이어야 빈이 생성되므로 loopback 에 뜨는 {@link MockWebServer}
     * 를 배포 기본값 자리에 놓을 수 없다 — 그래서 "구 주소는 못 받았다"가 아니라
     * <b>"정책이 거부하는 주소가 실제로 요청을 받았다"</b>로 판정한다.
     */
    @Test
    @DisplayName("★설정_override_는_VlmUrlPolicy_검증을_거치지_않는다 — 운영_등가_조건에서도_평문http_주소로_그대로_나간다(의도된 동작)")
    void configOverrideBypassesVlmUrlPolicy() throws Exception {
        MockWebServer overrideTarget = new MockWebServer();
        overrideTarget.start();
        try {
            // given — 운영 등가(prd + 완화 플래그 off) 정책. 배포 기본값은 그 정책을 통과하는 https 공인 대역
            //   (203.0.113.0/24 = TEST-NET-3, 실제로는 아무도 응답하지 않는다 — 여기로 나가면 안 된다는 뜻).
            VlmUrlPolicy policy = strictPolicy();
            String bootDefault = "https://203.0.113.10:9443";
            String override = overrideTarget.url("/").toString();   // 평문 http + loopback

            // and — 대조군: 같은 정책 인스턴스는 이 override 주소를 기동 시점이라면 거부한다.
            assertThatThrownBy(() -> policy.validate(override))
                    .as("기동 시점 정책은 평문 http·loopback 을 막는다 — 아래 전송이 그 정책 밖임을 보이는 대조군")
                    .isInstanceOf(IllegalStateException.class);

            WebClient client = cfg.vlmWebClient(bootDefault, "", policy, resolverReturning(override));
            overrideTarget.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));

            // when — 빈은 그대로 두고 호출한다.
            try {
                client.post().uri("/v1/videovlm-klid/describe").retrieve().bodyToMono(String.class)
                        .block(Duration.ofSeconds(5));
            } catch (RuntimeException ignored) {
                // 전송 여부는 아래 소켓 관측으로만 판정한다 — 응답 처리 실패로 판정이 흐려지지 않게 한다.
            }

            // then — 정책이 거부하는 주소가 실제로 요청을 받았다. 막히지 않는 것이 확정된 동작이다.
            RecordedRequest received = overrideTarget.takeRequest(5, TimeUnit.SECONDS);
            assertThat(received)
                    .as("override 주소가 요청을 받아야 한다 — 필터 배선이 빠지면 여기서 실패한다")
                    .isNotNull();
            assertThat(received.getPath()).isEqualTo("/v1/videovlm-klid/describe");
        } finally {
            overrideTarget.shutdown();
        }
    }
}
