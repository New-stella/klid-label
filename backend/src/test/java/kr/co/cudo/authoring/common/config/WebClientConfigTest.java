package kr.co.cudo.authoring.common.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WebClientConfig 의 VLM URL 검증(SSRF / HTTPS / placeholder fail-closed) 단위 테스트.
 *
 * <p>대상: {@link WebClientConfig#vlmWebClient(String, String, boolean)}
 *  - enabled=true 일 때 baseUrl 검증 적용
 *  - enabled=false 일 때 검증 생략 (개발 환경 영향 0)
 */
class WebClientConfigTest {

    private final WebClientConfig cfg = new WebClientConfig();

    @Test
    @DisplayName("WebClientConfig_vlm_enabled_false_시_검증_생략_localhost_허용")
    void enabledFalseSkipsValidation() {
        // given / when / then — enabled=false 면 어떤 URL 이든 빈 생성 성공
        assertThat(cfg.vlmWebClient("http://localhost:9400", "", false)).isNotNull();
        assertThat(cfg.vlmWebClient("", "", false)).isNotNull();
    }

    @Test
    @DisplayName("WebClientConfig_vlm_url_http_시_빈_생성_실패_HTTPS_강제")
    void httpSchemaRejectedWhenEnabled() {
        assertThatThrownBy(() -> cfg.vlmWebClient("http://vlm.example.com", "", true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HTTPS");
    }

    @Test
    @DisplayName("WebClientConfig_vlm_url_localhost_시_enabled_true_빈_생성_실패_SSRF_차단")
    void localhostRejectedWhenEnabled() {
        assertThatThrownBy(() -> cfg.vlmWebClient("https://localhost:9400", "", true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("내부");
    }

    @Test
    @DisplayName("WebClientConfig_vlm_url_private_IP_시_빈_생성_실패_SSRF_차단")
    void privateIpRejectedWhenEnabled() {
        assertThatThrownBy(() -> cfg.vlmWebClient("https://10.0.0.5", "", true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("내부");
        assertThatThrownBy(() -> cfg.vlmWebClient("https://192.168.1.10", "", true))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cfg.vlmWebClient("https://172.16.0.1", "", true))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cfg.vlmWebClient("https://169.254.169.254", "", true))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cfg.vlmWebClient("https://127.0.0.1", "", true))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("WebClientConfig_vlm_url_placeholder_시_빈_생성_실패_fail_closed")
    void placeholderRejectedWhenEnabled() {
        assertThatThrownBy(() -> cfg.vlmWebClient("", "", true))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cfg.vlmWebClient("https://example.com", "", true))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cfg.vlmWebClient("https://your-vlm-service", "", true))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("WebClientConfig_vlm_url_정상_HTTPS_공인_IP_시_빈_생성_성공")
    void validHttpsPublicIpAccepted() {
        // 공인 IP(8.8.8.8 — Google DNS) 직접 사용해 DNS 의존성 없이 검증 성공 케이스만 확인.
        assertThat(cfg.vlmWebClient("https://8.8.8.8/", "", true)).isNotNull();
    }
}
