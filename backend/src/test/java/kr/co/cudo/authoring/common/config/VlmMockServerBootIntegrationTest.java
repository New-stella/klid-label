package kr.co.cudo.authoring.common.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.reactive.function.client.WebClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * local/dev 의 VLM 실연동 배선(목 서버 평문 http)으로 <b>컨텍스트가 기동</b>하는지 검증한다.
 *
 * <p>배경: {@code vlm.client.enabled=true} 면 {@link WebClientConfig#vlmWebClient} 가 baseUrl 을
 * 검증하는데, 과거에는 HTTPS + 공인 호스트만 허용해 목 서버(TLS 미지원, 컨테이너 내부 호스트)를
 * 가리키면 <b>빈 생성 실패 → 애플리케이션 기동 자체가 실패</b>했다(2026-07-25 로컬 배선 시도 시
 * 실측·원복). 이 테스트는 "VLM 을 켜면 부팅이 깨진다"는 회귀를 막는다.
 *
 * <p>운영(prd/stg)·프로파일 미지정에서의 강제 보존 검증은 {@link WebClientConfigTest} 가 담당한다.
 * (테스트 클래스패스의 {@code src/test/resources/application-local.yml} 이 메인 프로파일 yml 을
 * 가리므로, 실제 배선값은 여기서 명시 주입한다 — 값 정합은 {@code DevProfileWiringGuardTest}.)
 */
@SpringBootTest
@ActiveProfiles("local")
@TestPropertySource(properties = {
        "vlm.client.enabled=true",
        "vlm.client.url=http://klid-mock-server:9400"
})
class VlmMockServerBootIntegrationTest {

    @Autowired(required = false)
    private WebClient vlmWebClient;

    @Test
    @DisplayName("통합_local_VLM활성_목서버_http주소로_기동성공")
    void mockServerHttpUrlBootsOnLocalProfile() {
        // given — VLM 활성 + 목 서버 http 주소로 컨텍스트 기동(@SpringBootTest 가 이미 기동 성공)
        // when / then
        assertThat(vlmWebClient)
                .as("VLM 활성 시 목 서버 주소로도 WebClient 빈이 생성돼야 한다(기동 차단 회귀 방지)")
                .isNotNull();
    }
}
