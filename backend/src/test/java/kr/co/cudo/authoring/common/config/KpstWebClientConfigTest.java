package kr.co.cudo.authoring.common.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link KpstWebClientConfig} 의 스키마 분기(http/https) + ca-cert 조건부 강제 단위 테스트.
 *
 * <p>UC018 — KPST 비식별 전송은 내부망 평문 http(IP) 또는 https+자체CA 둘 다 지원한다.
 * <ul>
 *   <li>http → SSL 미적용, ca-cert 불요(빈 기본값 OK).</li>
 *   <li>https → 자체 CA(ca.crt) 필수. 비었거나 못 읽으면 fail-closed 예외(CWE-295 보존).</li>
 *   <li>그 외 스키마(ftp 등)·스키마 없음·빈값 → 거부 예외.</li>
 * </ul>
 * 2개 빈(kpstDeidWebClient · kpstDeidProgressHttpClient)이 동일하게 분기한다.
 */
class KpstWebClientConfigTest {

    private static final String VALID_CA = "src/test/resources/kpst/test-ca.crt";

    private final KpstWebClientConfig cfg = new KpstWebClientConfig();

    @Test
    @DisplayName("Kpst_http_내부망IP_ca_cert_빈값_시_2개빈_생성성공_SSL미적용")
    void httpWithoutCaCertCreatesBeans() {
        // given — 내부망 평문 http base-url + ca-cert 빈값
        String httpUrl = "http://10.20.30.40:9989";
        // when / then — SSL/ca-cert 불요로 2개 빈 모두 예외 없이 생성
        assertThat(cfg.kpstDeidWebClient(httpUrl, "")).isNotNull();
        assertThat(cfg.kpstDeidProgressHttpClient(httpUrl, "")).isNotNull();
    }

    @Test
    @DisplayName("Kpst_https_유효한_ca_cert_시_2개빈_생성성공_SSL적용")
    void httpsWithValidCaCertCreatesBeans() {
        // given — https base-url + 유효 ca-cert
        String httpsUrl = "https://kpst-host:9201";
        // when / then
        assertThat(cfg.kpstDeidWebClient(httpsUrl, VALID_CA)).isNotNull();
        assertThat(cfg.kpstDeidProgressHttpClient(httpsUrl, VALID_CA)).isNotNull();
    }

    @Test
    @DisplayName("Kpst_https_ca_cert_빈값_시_fail_closed_예외_CWE295보존")
    void httpsWithoutCaCertFailsClosed() {
        // given — https 인데 ca-cert 빈값 → 신뢰 우회 차단(fail-closed)
        String httpsUrl = "https://kpst-host:9201";
        // when / then
        assertThatThrownBy(() -> cfg.kpstDeidWebClient(httpsUrl, ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ca-cert");
        assertThatThrownBy(() -> cfg.kpstDeidProgressHttpClient(httpsUrl, ""))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("Kpst_https_없는_ca_cert_경로_시_fail_closed_예외")
    void httpsWithUnreadableCaCertFailsClosed() {
        // given — https + 존재하지 않는 ca-cert 경로
        String httpsUrl = "https://kpst-host:9201";
        String missing = "src/test/resources/kpst/does-not-exist.crt";
        // when / then — 절대경로는 예외 메시지에 노출되지 않아야 한다(CWE-209)
        assertThatThrownBy(() -> cfg.kpstDeidWebClient(httpsUrl, missing))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("does-not-exist.crt");
    }

    @Test
    @DisplayName("Kpst_지원하지않는_스키마_ftp_시_거부예외")
    void unsupportedSchemeRejected() {
        // given / when / then — ftp 등은 http/https 외 거부
        assertThatThrownBy(() -> cfg.kpstDeidWebClient("ftp://kpst-host:21", ""))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cfg.kpstDeidProgressHttpClient("gopher://x", ""))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("Kpst_스키마없음_또는_빈_base_url_시_거부예외")
    void missingSchemeOrBlankRejected() {
        // given / when / then
        assertThatThrownBy(() -> cfg.kpstDeidWebClient("kpst-host:9201", ""))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cfg.kpstDeidWebClient("", ""))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cfg.kpstDeidProgressHttpClient("   ", ""))
                .isInstanceOf(IllegalStateException.class);
    }
}
