package kr.co.cudo.authoring.sysconfig.endpoint;

import kr.co.cudo.authoring.common.config.VlmUrlPolicy;
import kr.co.cudo.authoring.common.config.WebClientConfig;
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
 * ★ HIGH — <b>주소를 바꾸면 정적 자격증명이 새 호스트로 따라가지 않는다</b> (CWE-522).
 *
 * <h3>왜 이 테스트가 필요한가</h3>
 * <p>{@code WebClient.defaultHeader(...)} 는 <b>빈 생성 시점</b>에 고정되고, R11 의 URL 재작성 필터는
 * URL 만 바꾼다. 그래서 운영 화면에서 주소만 바꾸면 <b>원 수신처에 발급된 토큰이 그대로 새 호스트로
 * 전송</b>됐다 — 화면 한 줄로 임의의 수신처에 자격증명이 흘러가는 상태다.
 *
 * <p>고른 대응(PM 결정)은 <b>"호스트가 다르면 헤더를 떼고 WARN"</b> 이다. 그 토큰은 다른 호스트에서
 * 어차피 무효라 떼면 상대가 401 로 <b>시끄럽게 실패</b>하고, 안 떼면 <b>조용히 유출</b>된다.
 *
 * <p><b>판정 축은 호스트</b>다 — 포트·경로만 달라지는 정당한 구성 변경에서 자격증명이 끊기면
 * 기능이 깨지므로, 같은 호스트의 다른 포트에는 그대로 붙어야 한다(아래 두 번째 케이스).
 *
 * <p><b>mutation 확인</b>: {@code WebClientConfig} 에서
 * {@code IntegrationEndpointTransportGuards.stripCredentialOnHostChange(...)} 배선을 지우면
 * "다른 호스트" 케이스가 "헤더가 여전히 붙어 있다"로 실패한다.
 */
class IntegrationEndpointCredentialGuardTest {

    private static final String VLM_TOKEN = "vlm-secret-token";
    private static final String CONTROL_TOKEN = "control-secret-token";

    private MockWebServer bootServer;
    private MockWebServer otherServer;
    private SystemConfigService systemConfigService;
    private final WebClientConfig config = new WebClientConfig();

    /**
     * local 목업 등가(완화) 정책 — 이 테스트의 관심사는 자격증명이지 URL 정책이 아니다.
     * {@code enabled=false} 로 호출하더라도 평문 http 경고 경로가 이 인스턴스를 쓴다.
     */
    private static VlmUrlPolicy relaxedPolicy() {
        org.springframework.mock.env.MockEnvironment env = new org.springframework.mock.env.MockEnvironment();
        env.setActiveProfiles("local");
        return new VlmUrlPolicy(env, true);
    }

    @BeforeEach
    void setUp() throws IOException {
        bootServer = new MockWebServer();
        bootServer.start();
        otherServer = new MockWebServer();
        otherServer.start();
        systemConfigService = mock(SystemConfigService.class);
    }

    @AfterEach
    void tearDown() throws IOException {
        bootServer.shutdown();
        otherServer.shutdown();
    }

    private IntegrationEndpointResolver resolver() {
        @SuppressWarnings("unchecked")
        ObjectProvider<SystemConfigService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(systemConfigService);
        return new IntegrationEndpointResolver(provider);
    }

    private void override(String url) {
        when(systemConfigService.findString(anyString())).thenReturn(Optional.of(url));
    }

    /**
     * MockWebServer 는 {@code http://localhost:port} 를 준다. 같은 프로세스의 두 서버는 호스트가 같아
     * "호스트가 달라진 상황"을 만들 수 없으므로, 루프백의 <b>다른 표기</b>({@code 127.0.0.1})로
     * 주소를 만든다 — 도달은 되면서 호스트 문자열은 달라진다.
     */
    private String differentHostUrlOf(MockWebServer server) {
        return "http://127.0.0.1:" + server.getPort();
    }

    private RecordedRequest exchange(WebClient client, MockWebServer expected) throws InterruptedException {
        expected.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));
        client.post().uri("/v1/videovlm/verify").bodyValue("{}")
                .retrieve().bodyToMono(String.class).block(Duration.ofSeconds(5));
        return expected.takeRequest(5, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("★VLM — 호스트가_바뀌면_Authorization_토큰을_붙이지_않는다")
    void vlmTokenIsStrippedWhenHostChanges() throws Exception {
        // given — 배포 기본값(토큰이 발급된 원 수신처)으로 만들어진 빈
        String bootDefault = bootServer.url("/").toString();
        WebClient client = config.vlmWebClient(bootDefault, VLM_TOKEN, false, relaxedPolicy(), resolver());
        override(differentHostUrlOf(otherServer));

        // when
        RecordedRequest received = exchange(client, otherServer);

        // then — 새 호스트로 나가되 자격증명은 실리지 않는다
        assertThat(received).as("새 주소가 요청을 받아야 한다").isNotNull();
        assertThat(received.getHeader("Authorization"))
                .as("원 수신처에 발급된 토큰이 새 호스트로 따라가면 안 된다(CWE-522)")
                .isNull();
    }

    @Test
    @DisplayName("VLM — 같은_호스트의_다른_포트면_토큰은_그대로_붙는다 — 포트·경로_차이로_떼지_않는다")
    void vlmTokenSurvivesPortOnlyChange() throws Exception {
        String bootDefault = bootServer.url("/").toString();
        WebClient client = config.vlmWebClient(bootDefault, VLM_TOKEN, false, relaxedPolicy(), resolver());
        override(otherServer.url("/").toString()); // 같은 localhost, 다른 포트

        RecordedRequest received = exchange(client, otherServer);

        assertThat(received).isNotNull();
        assertThat(received.getHeader("Authorization")).isEqualTo("Bearer " + VLM_TOKEN);
    }

    @Test
    @DisplayName("VLM — override_가_없으면_토큰은_그대로다 — 기존_형상_영향_0")
    void vlmTokenIntactWithoutOverride() throws Exception {
        String bootDefault = bootServer.url("/").toString();
        WebClient client = config.vlmWebClient(bootDefault, VLM_TOKEN, false, relaxedPolicy(), resolver());
        when(systemConfigService.findString(anyString())).thenReturn(Optional.empty());

        RecordedRequest received = exchange(client, bootServer);

        assertThat(received).isNotNull();
        assertThat(received.getHeader("Authorization")).isEqualTo("Bearer " + VLM_TOKEN);
    }

    @Test
    @DisplayName("★관제_통지 — 호스트가_바뀌면_x-access-token_을_붙이지_않는다")
    void controlNotifyTokenIsStrippedWhenHostChanges() throws Exception {
        String bootDefault = bootServer.url("/").toString();
        WebClient client = config.controlNotifyWebClient(bootDefault, CONTROL_TOKEN, true, resolver());
        override(differentHostUrlOf(otherServer));

        RecordedRequest received = exchange(client, otherServer);

        assertThat(received).isNotNull();
        assertThat(received.getHeader("x-access-token")).isNull();
    }

    @Test
    @DisplayName("관제_통지 — 같은_호스트면_토큰은_그대로_붙는다")
    void controlNotifyTokenSurvivesPortOnlyChange() throws Exception {
        String bootDefault = bootServer.url("/").toString();
        WebClient client = config.controlNotifyWebClient(bootDefault, CONTROL_TOKEN, true, resolver());
        override(otherServer.url("/").toString());

        RecordedRequest received = exchange(client, otherServer);

        assertThat(received).isNotNull();
        assertThat(received.getHeader("x-access-token")).isEqualTo(CONTROL_TOKEN);
    }
}
