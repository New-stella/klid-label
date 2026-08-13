package kr.co.cudo.authoring.sysconfig.endpoint;

import kr.co.cudo.authoring.common.config.KpstWebClientConfig;
import kr.co.cudo.authoring.sysconfig.ConfigKeys;
import kr.co.cudo.authoring.sysconfig.service.SystemConfigService;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ★ 비식별 주소가 <b>실제로 위탁이 나가는 빈</b>에 반영되는지 (R11, 2026-08-10 키 교체).
 *
 * <h3>왜 이 테스트가 따로 필요한가</h3>
 * <p>구 설계는 「비식별 서버」를 {@code authoring.integration.deidentify.base-url} 에 걸었는데, 그 속성이
 * 구동하는 빈은 <b>주입 대상이 0건</b>이라 값을 바꿔도 위탁 주소가 달라지지 않았다. 실제 위탁은
 * {@code KpstDeidentifyClient} 가 받는 {@code kpstDeidWebClient} 로 나간다.
 *
 * <p>즉 이 테스트가 고정하는 것은 "설정이 저장되는가"가 아니라 <b>"바로 그 빈이 새 주소로 나가는가"</b>다.
 * 배선을 다시 엉뚱한 빈으로 옮기면 여기서 실패한다.
 *
 * <p>⚠ TLS 구성(자체 CA 신뢰)은 기동 시점 스킴으로 고정되어 주소를 바꿔도 따라가지 않는다 —
 * 이 테스트는 평문 http 형상만 다룬다(그 한계는 {@code KpstWebClientConfig} 에 문서화돼 있다).
 */
class KpstEndpointImmediateEffectTest {

    private MockWebServer oldServer;
    private MockWebServer newServer;
    private SystemConfigService systemConfigService;

    @BeforeEach
    void setUp() throws IOException {
        oldServer = new MockWebServer();
        oldServer.start();
        newServer = new MockWebServer();
        newServer.start();
        systemConfigService = mock(SystemConfigService.class);
    }

    @AfterEach
    void tearDown() throws IOException {
        oldServer.shutdown();
        newServer.shutdown();
    }

    private IntegrationEndpointResolver resolver() {
        @SuppressWarnings("unchecked")
        ObjectProvider<SystemConfigService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(systemConfigService);
        return new IntegrationEndpointResolver(provider);
    }

    @Test
    @DisplayName("★비식별 주소를 바꾸면 KPST 위탁 클라이언트가 새 주소로 나간다")
    void kpstClientFollowsConfiguredAddress() throws Exception {
        // given — 배포 기본값(구 주소)으로 만들어진 위탁용 빈
        String bootDefault = oldServer.url("/").toString();
        WebClient client = new KpstWebClientConfig()
                .kpstDeidWebClient(bootDefault, "", resolver());
        newServer.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));

        // when — 설정이 새 주소로 바뀐 뒤 호출
        when(systemConfigService.findString(anyString())).thenReturn(Optional.of(newServer.url("/").toString()));
        client.post().uri("/project").bodyValue("{}").retrieve()
                .bodyToMono(String.class).block(Duration.ofSeconds(5));

        // then
        RecordedRequest received = newServer.takeRequest(5, TimeUnit.SECONDS);
        assertThat(received).as("새 주소가 위탁 요청을 받아야 한다").isNotNull();
        assertThat(received.getPath()).isEqualTo("/project");
        assertThat(oldServer.takeRequest(300, TimeUnit.MILLISECONDS))
                .as("구 주소로는 더 이상 나가지 않아야 한다").isNull();
    }

    @Test
    @DisplayName("설정이 없으면 배포 기본값 그대로 — 기존 형상 영향 0")
    void fallsBackToDeploymentDefault() throws Exception {
        String bootDefault = oldServer.url("/").toString();
        WebClient client = new KpstWebClientConfig()
                .kpstDeidWebClient(bootDefault, "", resolver());
        oldServer.enqueue(new MockResponse().setResponseCode(200).setBody("Connect"));
        // 행 없음(=정상 상태) — 리졸버는 부재를 값으로 받는다(예외는 캐시되지 않는다, MED-4).
        when(systemConfigService.findString(anyString())).thenReturn(Optional.empty());

        client.get().uri("/").retrieve().bodyToMono(String.class).block(Duration.ofSeconds(5));

        assertThat(oldServer.takeRequest(5, TimeUnit.SECONDS)).isNotNull();
    }

    @Test
    @DisplayName("비식별 설정 키는 실제 위탁 속성명과 같다 — 실효 0 인 구 키를 쓰지 않는다")
    void endpointKeyPointsAtTheEffectiveProperty() {
        assertThat(IntegrationEndpoint.DEIDENTIFY.configKey()).isEqualTo("kpst.deid.base-url");
        assertThat(ConfigKeys.ALLOWED).contains("kpst.deid.base-url");
        // 구 키는 설정 화이트리스트에서 빠졌다(실효 0 인 칸을 화면에 남기지 않는다).
        assertThat(ConfigKeys.ALLOWED).doesNotContain("authoring.integration.deidentify.base-url");
    }
}
