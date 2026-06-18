package kr.co.cudo.authoring.common.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * UC018 — KPST enabled=true + 내부망 평문 http base-url(ca-cert 없음) 기동 통합 테스트.
 *
 * <p>https 가 아닌 http base-url 로도 {@link KpstWebClientConfig} 의 3개 빈이 ca-cert 없이 정상 로드되어
 * 컨텍스트가 기동함을 검증한다(http 배포 cert 없이 부팅 보장). https fail-closed 보존 검증은
 * {@link KpstWebClientConfigTest} 단위 테스트가 담당한다.
 */
@SpringBootTest
@ActiveProfiles("local")
@TestPropertySource(properties = {
        "kpst.deid.enabled=true",
        "kpst.deid.base-url=http://10.20.30.40:9989",
        "kpst.deid.ca-cert-path=",
        // 폴링 잡 트리거가 통합 검증 중 자동 발화하지 않도록 충분히 늦춘다.
        "kpst.deid.poll-interval-sec=86400"
})
class KpstHttpBootIntegrationTest {

    @Autowired(required = false)
    private WebClient kpstDeidWebClient;
    @Autowired(required = false)
    private WebClient kpstDeidUploadWebClient;
    @Autowired(required = false)
    private HttpClient kpstDeidProgressHttpClient;

    @Test
    @DisplayName("통합_enabled_true_http_base_url_ca_cert없이_3개빈_기동성공")
    void httpBaseUrlBootsWithoutCaCert() {
        // given — http base-url + ca-cert 빈값으로 컨텍스트 기동(@SpringBootTest 가 이미 기동 성공)
        // when / then — 3개 빈이 SSL 미적용으로 정상 로드
        assertThat(kpstDeidWebClient).isNotNull();
        assertThat(kpstDeidUploadWebClient).isNotNull();
        assertThat(kpstDeidProgressHttpClient).isNotNull();
    }
}
