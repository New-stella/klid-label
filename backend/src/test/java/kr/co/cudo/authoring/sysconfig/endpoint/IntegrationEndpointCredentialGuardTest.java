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
import java.net.URI;
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
     * 연동 주소 검증 정책 — 이 테스트의 관심사는 자격증명이지 URL 정책이 아니다.
     * 평문 http 경고 경로가 이 인스턴스를 쓴다(정책은 프로파일로 갈리지 않는다).
     */
    private static VlmUrlPolicy relaxedPolicy() {
        return new VlmUrlPolicy();
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
        client.post().uri("/v1/videovlm-klid/describe").bodyValue("{}")
                .retrieve().bodyToMono(String.class).block(Duration.ofSeconds(5));
        return expected.takeRequest(5, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("★VLM — 호스트가_바뀌면_Authorization_토큰을_붙이지_않는다")
    void vlmTokenIsStrippedWhenHostChanges() throws Exception {
        // given — 배포 기본값(토큰이 발급된 원 수신처)으로 만들어진 빈
        String bootDefault = bootServer.url("/").toString();
        WebClient client = config.vlmWebClient(bootDefault, VLM_TOKEN, relaxedPolicy(), resolver());
        override(differentHostUrlOf(otherServer));

        // when
        RecordedRequest received = exchange(client, otherServer);

        // then — 새 호스트로 나가되 자격증명은 실리지 않는다
        assertThat(received).as("새 주소가 요청을 받아야 한다").isNotNull();
        assertThat(received.getHeader("Authorization"))
                .as("원 수신처에 발급된 토큰이 새 호스트로 따라가면 안 된다(CWE-522)")
                .isNull();
    }

    /**
     * ★ HIGH — <b>원장에서 고른 장비로 핀된 요청은 자격증명을 유지한다</b>.
     *
     * <p>구 동작은 최종 URL 의 호스트를 배포 기본값과만 비교했다. 그런데 한 벤더가 <b>여러 장비로
     * 이중화</b>되면 배포 기본값과 같은 호스트는 <b>최대 하나</b>라, 나머지 장비로 가는 <b>정상 위탁이
     * 전량 무인증</b>으로 나갔다 — 벤더 401 → 비재시도 확정 실패 → 그 영상은 시계열 결과를 영영 얻지
     * 못한다. 가드의 전제(「운영자가 다른 시스템으로 주소를 바꿨다」)가 「같은 벤더의 두 번째 장비」에는
     * 맞지 않는다.
     *
     * <p>⚠ 이 축이 지금까지 시험되지 않은 이유: 노드 배선 시험들이 {@code token=""} 으로 빈을 만들어
     * <b>그 필터를 아예 등록하지 않았다</b>. 그래서 토큰이 설정된 형상으로 세운다.
     */
    @Test
    @DisplayName("★VLM — 원장에서_고른_장비로_핀된_요청에는_인증_헤더가_유지된다")
    void vlmTokenSurvivesPinnedNodeTarget() throws Exception {
        // given — 토큰이 설정된 배포 형상 + 배포 기본값과 <b>다른 호스트</b>의 장비를 원장에서 골랐다.
        String bootDefault = bootServer.url("/").toString();
        WebClient client = config.vlmWebClient(bootDefault, VLM_TOKEN, relaxedPolicy(), resolver());
        otherServer.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));

        // when — VlmClient 가 붙이는 것과 같은 표식 + 절대 목적지.
        client.post()
                .uri(URI.create(differentHostUrlOf(otherServer) + "/v1/videovlm-klid/describe"))
                .attributes(a -> a.put(IntegrationEndpointExchangeFilter.EXPLICIT_TARGET_ATTRIBUTE,
                        Boolean.TRUE))
                .bodyValue("{}")
                .retrieve().bodyToMono(String.class).block(Duration.ofSeconds(5));

        // then
        RecordedRequest received = otherServer.takeRequest(5, TimeUnit.SECONDS);
        assertThat(received).as("고른 장비가 요청을 받아야 한다").isNotNull();
        assertThat(received.getHeader("Authorization"))
                .as("이중화된 두 번째 장비로 가는 정상 위탁이 무인증이 되면 전량 401 로 확정 실패한다")
                .isEqualTo("Bearer " + VLM_TOKEN);
    }

    /**
     * ★ 반대 방향 — <b>표식이 없는 임의의 호스트 변경</b>에는 종전대로 자격증명을 뗀다.
     *
     * <p>이 케이스가 없으면 위 예외가 가드를 통째로 무력화한 것인지 구분되지 않는다.
     */
    @Test
    @DisplayName("★VLM — 표식_없는_임의의_호스트_변경에는_여전히_인증_헤더를_뗀다")
    void vlmTokenStillStrippedWithoutPinMarker() throws Exception {
        // given — 같은 목적지지만 표식이 없다(운영 화면 override 로 주소만 바뀐 상황).
        String bootDefault = bootServer.url("/").toString();
        WebClient client = config.vlmWebClient(bootDefault, VLM_TOKEN, relaxedPolicy(), resolver());
        otherServer.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));

        // when
        client.post()
                .uri(URI.create(differentHostUrlOf(otherServer) + "/v1/videovlm-klid/describe"))
                .bodyValue("{}")
                .retrieve().bodyToMono(String.class).block(Duration.ofSeconds(5));

        // then
        RecordedRequest received = otherServer.takeRequest(5, TimeUnit.SECONDS);
        assertThat(received).isNotNull();
        assertThat(received.getHeader("Authorization"))
                .as("표식 없는 호스트 변경까지 통과시키면 CWE-522 가 그대로 열린다")
                .isNull();
    }

    @Test
    @DisplayName("VLM — 같은_호스트의_다른_포트면_토큰은_그대로_붙는다 — 포트·경로_차이로_떼지_않는다")
    void vlmTokenSurvivesPortOnlyChange() throws Exception {
        String bootDefault = bootServer.url("/").toString();
        WebClient client = config.vlmWebClient(bootDefault, VLM_TOKEN, relaxedPolicy(), resolver());
        override(otherServer.url("/").toString()); // 같은 localhost, 다른 포트

        RecordedRequest received = exchange(client, otherServer);

        assertThat(received).isNotNull();
        assertThat(received.getHeader("Authorization")).isEqualTo("Bearer " + VLM_TOKEN);
    }

    @Test
    @DisplayName("VLM — override_가_없으면_토큰은_그대로다 — 기존_형상_영향_0")
    void vlmTokenIntactWithoutOverride() throws Exception {
        String bootDefault = bootServer.url("/").toString();
        WebClient client = config.vlmWebClient(bootDefault, VLM_TOKEN, relaxedPolicy(), resolver());
        when(systemConfigService.findString(anyString())).thenReturn(Optional.empty());

        RecordedRequest received = exchange(client, bootServer);

        assertThat(received).isNotNull();
        assertThat(received.getHeader("Authorization")).isEqualTo("Bearer " + VLM_TOKEN);
    }

    @Test
    @DisplayName("★관제_통지 — 호스트가_바뀌면_x-access-token_을_붙이지_않는다")
    void controlNotifyTokenIsStrippedWhenHostChanges() throws Exception {
        String bootDefault = bootServer.url("/").toString();
        WebClient client = config.controlNotifyWebClient(bootDefault, CONTROL_TOKEN, true, resolver(), null);
        override(differentHostUrlOf(otherServer));

        RecordedRequest received = exchange(client, otherServer);

        assertThat(received).isNotNull();
        assertThat(received.getHeader("x-access-token")).isNull();
    }

    @Test
    @DisplayName("관제_통지 — 같은_호스트면_토큰은_그대로_붙는다")
    void controlNotifyTokenSurvivesPortOnlyChange() throws Exception {
        String bootDefault = bootServer.url("/").toString();
        WebClient client = config.controlNotifyWebClient(bootDefault, CONTROL_TOKEN, true, resolver(), null);
        override(otherServer.url("/").toString());

        RecordedRequest received = exchange(client, otherServer);

        assertThat(received).isNotNull();
        assertThat(received.getHeader("x-access-token")).isEqualTo(CONTROL_TOKEN);
    }
}
