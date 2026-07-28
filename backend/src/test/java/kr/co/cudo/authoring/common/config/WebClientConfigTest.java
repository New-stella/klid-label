package kr.co.cudo.authoring.common.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WebClientConfig 의 VLM URL 검증(SSRF / HTTPS / placeholder fail-closed) 단위 테스트.
 *
 * <p>대상: {@link WebClientConfig#vlmWebClient(String, String, boolean, VlmUrlPolicy)}
 *  - enabled=true 일 때 baseUrl 검증 적용 (정책 판정은 {@link VlmUrlPolicy} 단일 원천)
 *  - enabled=false 일 때 검증 생략 (개발 환경 영향 0)
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

    @Test
    @DisplayName("WebClientConfig_vlm_enabled_false_시_검증_생략_localhost_허용")
    void enabledFalseSkipsValidation() {
        // given / when / then — enabled=false 면 어떤 URL 이든 빈 생성 성공
        assertThat(cfg.vlmWebClient("http://localhost:9400", "", false, strictPolicy())).isNotNull();
        assertThat(cfg.vlmWebClient("", "", false, strictPolicy())).isNotNull();
    }

    @Test
    @DisplayName("WebClientConfig_vlm_url_http_시_빈_생성_실패_HTTPS_강제")
    void httpSchemaRejectedWhenEnabled() {
        assertThatThrownBy(() -> cfg.vlmWebClient("http://vlm.vendor.io", "", true, strictPolicy()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HTTPS");
    }

    @Test
    @DisplayName("WebClientConfig_vlm_url_localhost_시_enabled_true_빈_생성_실패_SSRF_차단")
    void localhostRejectedWhenEnabled() {
        assertThatThrownBy(() -> cfg.vlmWebClient("https://localhost:9400", "", true, strictPolicy()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("내부");
    }

    @Test
    @DisplayName("WebClientConfig_vlm_url_private_IP_시_빈_생성_실패_SSRF_차단")
    void privateIpRejectedWhenEnabled() {
        assertThatThrownBy(() -> cfg.vlmWebClient("https://10.0.0.5", "", true, strictPolicy()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("내부");
        assertThatThrownBy(() -> cfg.vlmWebClient("https://192.168.1.10", "", true, strictPolicy()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cfg.vlmWebClient("https://172.16.0.1", "", true, strictPolicy()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cfg.vlmWebClient("https://169.254.169.254", "", true, strictPolicy()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cfg.vlmWebClient("https://127.0.0.1", "", true, strictPolicy()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("WebClientConfig_vlm_url_placeholder_시_빈_생성_실패_fail_closed")
    void placeholderRejectedWhenEnabled() {
        assertThatThrownBy(() -> cfg.vlmWebClient("", "", true, strictPolicy()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cfg.vlmWebClient("https://example.com", "", true, strictPolicy()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cfg.vlmWebClient("https://your-vlm-service", "", true, strictPolicy()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("WebClientConfig_vlm_url_정상_HTTPS_공인_IP_시_빈_생성_성공")
    void validHttpsPublicIpAccepted() {
        // 공인 IP(8.8.8.8 — Google DNS) 직접 사용해 DNS 의존성 없이 검증 성공 케이스만 확인.
        assertThat(cfg.vlmWebClient("https://8.8.8.8/", "", true, strictPolicy())).isNotNull();
    }

    @Test
    @DisplayName("WebClientConfig_vlm_local_완화정책_시_평문_HTTP_사설IP_목업_URL_빈_생성_성공")
    void relaxedPolicyAcceptsPlaintextMockUrl() {
        assertThat(cfg.vlmWebClient("http://klid-mock-server:9400", "", true, relaxedPolicy())).isNotNull();
        assertThat(cfg.vlmWebClient("http://127.0.0.1:9400", "", true, relaxedPolicy())).isNotNull();
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
            assertThat(cfg.vlmWebClient("http://klid-mock-server:9400", "secret-token-value", true,
                    relaxedPolicy())).isNotNull();

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
            assertThat(cfg.vlmWebClient("https://8.8.8.8/", "secret-token-value", true, strictPolicy()))
                    .isNotNull();
            assertThat(appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(m -> m.contains("tokenLength")))
                    .isEmpty();
        } finally {
            logger.detachAppender(appender);
        }
    }
}
