package kr.co.cudo.authoring.common.client;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import kr.co.cudo.authoring.common.client.dto.VlmTimeseriesRequest;
import kr.co.cudo.authoring.common.config.VlmUrlPolicy;
import kr.co.cudo.authoring.common.config.WebClientConfig;
import kr.co.cudo.authoring.sysconfig.endpoint.IntegrationEndpointResolver;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ★ <b>배선 시험</b> — 「고른 장비」와 「실제로 요청을 받은 곳」이 같은가. [@design ADR-057]
 *
 * <h3>왜 이 시험이 필요한가</h3>
 * <p>이 저장소는 방금 같은 형태의 결함을 겪었다 — 값을 <b>계산하는</b> 코드는 시험이 촘촘히 덮고 있는데
 * 그 값이 <b>실제 요청에 실리는지</b>는 아무도 보지 않아, 배선 세 줄을 지워도 시험 수천 건이 전부
 * 초록이었다. 노드 분산도 정확히 같은 모양이다: 장비를 고르는 로직은 단위 시험이 덮지만, 고른 주소가
 * 요청의 <b>목적지</b>가 되지 않으면 위탁 원장에는 「A 로 보냈다」가 남고 요청은 B 로 간다 — 오류가
 * 아니라 <b>조용한 어긋남</b>이라 로그에도 남지 않는다.
 *
 * <p>그래서 상수값이 아니라 <b>실제 소켓 두 개</b>를 띄우고 어느 쪽이 요청을 받았는지로 판정한다.
 *
 * <p><b>mutation 확인</b>: {@code VlmClient.submit} 에서 절대 URI 배선(장비 주소 -> {@code uri(...)})을
 * 지우면 이 시험은 "고른 장비가 요청을 못 받았다"로 실패한다.
 */
class VlmClientNodeTargetTest {

    /** 배포 기본 주소로 뜬 서버 — 장비를 고르지 못했을 때만 요청을 받아야 한다. */
    private MockWebServer bootDefault;
    /** 선택기가 고른 장비. */
    private MockWebServer chosenNode;

    private CircuitBreakerRegistry cbRegistry;
    private RetryRegistry retryRegistry;

    @BeforeEach
    void setUp() throws IOException {
        bootDefault = new MockWebServer();
        bootDefault.start();
        chosenNode = new MockWebServer();
        chosenNode.start();
        cbRegistry = CircuitBreakerRegistry.of(CircuitBreakerConfig.ofDefaults());
        retryRegistry = RetryRegistry.of(RetryConfig.custom().maxAttempts(1).build());
    }

    @AfterEach
    void tearDown() throws IOException {
        bootDefault.shutdown();
        chosenNode.shutdown();
    }

    private VlmClient client(WebClient webClient) {
        return new VlmClient(webClient, cbRegistry, retryRegistry, 5L);
    }

    private VlmClient plainClient() {
        return client(WebClient.builder().baseUrl(bootDefault.url("/").toString()).build());
    }

    private static VlmTimeseriesRequest req(String requestId) {
        return VlmTimeseriesRequest.ofFrameInterval(requestId, "fall", "/data/videos/deid.mp4",
                "http://localhost:8080/api/v1/vlm/callback");
    }

    private static MockResponse accepted(String requestId) {
        return new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"request_id\":\"" + requestId + "\",\"status\":\"accepted\"}");
    }

    @Test
    @DisplayName("★고른_장비의_주소로_요청이_나간다 — 배포 기본 주소는 받지 않는다")
    void 고른_장비의_주소로_요청이_나간다() throws Exception {
        // given
        chosenNode.enqueue(accepted("req-node"));
        bootDefault.enqueue(accepted("req-node"));

        // when
        plainClient().submitDescribe(req("req-node"), chosenNode.url("/").toString())
                .block(Duration.ofSeconds(5));

        // then — 고른 장비가 실제로 받았다.
        RecordedRequest received = chosenNode.takeRequest(5, TimeUnit.SECONDS);
        assertThat(received).as("고른 장비가 요청을 받아야 한다").isNotNull();
        assertThat(received.getPath()).isEqualTo(VlmClient.DESCRIBE_PATH);
        assertThat(bootDefault.takeRequest(300, TimeUnit.MILLISECONDS))
                .as("배포 기본 주소로는 나가지 않아야 한다 — 원장 기록과 실제 목적지가 갈린다").isNull();
    }

    @Test
    @DisplayName("추가_질문_창구도_같은_장비로_나간다")
    void 추가_질문_창구도_같은_장비로_나간다() throws Exception {
        // given
        chosenNode.enqueue(accepted("req-sub"));

        // when
        plainClient().submitCustom(req("req-sub"), chosenNode.url("/").toString())
                .block(Duration.ofSeconds(5));

        // then
        RecordedRequest received = chosenNode.takeRequest(5, TimeUnit.SECONDS);
        assertThat(received).isNotNull();
        // 추가 질문 축의 창구는 custom 이다 — 그 창구는 이벤트 유형 대신 질문 문구를 싣는다.
        //   ⚠ 구 경로 상수(describe-sub)는 벤더가 계속 제공하지만 <b>우리가 부르지 않는다</b>.
        assertThat(received.getPath()).isEqualTo(VlmClient.CUSTOM_PATH);
    }

    @Test
    @DisplayName("장비를_고르지_못하면_배포_기본_주소로_그대로_나간다 — 기존 형상 영향 0")
    void 장비를_고르지_못하면_배포_기본_주소로_나간다() throws Exception {
        // given — 원장에 시계열 노드가 한 건도 없는 현재 형상.
        bootDefault.enqueue(accepted("req-none"));

        // when
        plainClient().submitDescribe(req("req-none"), null).block(Duration.ofSeconds(5));

        // then
        RecordedRequest received = bootDefault.takeRequest(5, TimeUnit.SECONDS);
        assertThat(received).isNotNull();
        assertThat(received.getPath()).isEqualTo(VlmClient.DESCRIBE_PATH);
    }

    @Test
    @DisplayName("장비_주소의_후행_슬래시가_경로를_망가뜨리지_않는다")
    void 장비_주소의_후행_슬래시가_경로를_망가뜨리지_않는다() throws Exception {
        // given — 원장에 주소가 "…:9300/" 형태로 들어와 있을 수 있다.
        chosenNode.enqueue(accepted("req-slash"));
        String addrWithSlash = chosenNode.url("/").toString();
        assertThat(addrWithSlash).endsWith("/");

        // when
        plainClient().submitDescribe(req("req-slash"), addrWithSlash).block(Duration.ofSeconds(5));

        // then — 경로가 "//v1/..." 이 되면 벤더가 404 를 준다.
        RecordedRequest received = chosenNode.takeRequest(5, TimeUnit.SECONDS);
        assertThat(received).isNotNull();
        assertThat(received.getPath()).isEqualTo(VlmClient.DESCRIBE_PATH);
    }

    /**
     * ★ 운영 화면에서 연동 주소를 바꿔 두어도 <b>고른 장비가 이긴다</b>.
     *
     * <p>주소 override 는 <b>연동 하나당 주소 하나</b>다. 장비 목록·주소의 진실원은 노드 원장이고,
     * 설정값은 원장이 비었을 때의 씨앗일 뿐 그 뒤로는 원장이 이긴다. 여기서 override 가 이기면
     * 위탁 원장에는 「그 장비로 보냈다」가 남는데 요청은 다른 곳으로 가 <b>기록이 거짓말을 한다</b>.
     */
    @Test
    @DisplayName("★연동주소_override_가_있어도_고른_장비를_덮지_않는다")
    void 연동주소_override_가_있어도_고른_장비를_덮지_않는다() throws Exception {
        // given — 운영 화면에서 VLM 주소를 bootDefault 아닌 다른 곳으로 바꿔 두었다.
        MockWebServer overridden = new MockWebServer();
        overridden.start();
        try {
            SystemConfigService configService = mock(SystemConfigService.class);
            when(configService.findString(anyString()))
                    .thenReturn(Optional.of(overridden.url("/").toString()));
            @SuppressWarnings("unchecked")
            ObjectProvider<SystemConfigService> provider = mock(ObjectProvider.class);
            when(provider.getIfAvailable()).thenReturn(configService);

            WebClient webClient = new WebClientConfig().vlmWebClient(
                    bootDefault.url("/").toString(), "", new VlmUrlPolicy(),
                    new IntegrationEndpointResolver(provider));
            chosenNode.enqueue(accepted("req-pinned"));
            overridden.enqueue(accepted("req-pinned"));

            // when — 선택기가 고른 장비로 보낸다.
            client(webClient).submitDescribe(req("req-pinned"), chosenNode.url("/").toString())
                    .block(Duration.ofSeconds(5));

            // then
            assertThat(chosenNode.takeRequest(5, TimeUnit.SECONDS))
                    .as("고른 장비가 요청을 받아야 한다").isNotNull();
            assertThat(overridden.takeRequest(300, TimeUnit.MILLISECONDS))
                    .as("override 주소가 고른 장비를 덮으면 원장 기록이 거짓이 된다").isNull();
        } finally {
            overridden.shutdown();
        }
    }

    /**
     * ★ <b>주소는 있는데 목적지를 만들 수 없으면 조용히 되돌리지 않는다</b>.
     *
     * <p>{@code null} 로 되돌려 배포 기본 주소로 보내면, 호출자는 원장에 「그 장비로 보냈다」를 적어
     * 둔 채 요청만 다른 곳으로 나가 <b>기록이 거짓말</b>을 한다(오류가 아니라 조용한 어긋남).
     *
     * <p>실패의 성질은 <b>비재시도</b>다 — 같은 주소로 다시 보내도 결과가 같다.
     */
    @Test
    @DisplayName("★목적지를_만들_수_없는_주소면_배포_기본값으로_되돌아가지_않고_실패한다")
    void 목적지를_만들_수_없으면_되돌아가지_않는다() throws Exception {
        // given — 원장 CHECK 는 NOT NULL 만 걸어 공백·스킴 없는 주소가 통과한다.
        VlmClient client = plainClient();

        // when / then
        assertThatThrownBy(() -> client.submitDescribe(req("req-bad"), "ts02.internal:9500")
                .block(Duration.ofSeconds(5)))
                .isInstanceOf(NonRetryableExternalException.class);

        assertThat(bootDefault.takeRequest(300, TimeUnit.MILLISECONDS))
                .as("배포 기본 주소로 되돌아가면 원장 기록과 실제 목적지가 갈린다").isNull();
    }

    /**
     * ★ 그 실패의 메시지에 <b>주소를 싣지 않는다</b>.
     *
     * <p>제출 실패 사유는 처리 이력 컬럼({@code ERROR_MSG})에 <b>sanitize·절단 없이</b> 영속될 수
     * 있다. 장비 주소는 내부 토폴로지이고(CWE-497), 주소에 개행이 섞이면 로그 위조 통로가 된다
     * (CWE-117). 위탁 스텝이 이미 「주소 값은 싣지 않는다」를 지키고 있으므로 클라이언트도 같아야 한다.
     */
    @Test
    @DisplayName("★목적지_해석_실패_메시지에_장비_주소를_싣지_않는다")
    void 목적지_해석_실패_메시지에_주소를_싣지_않는다() {
        // 구 코드는 이 값을 URI.create 에 그대로 넘겼고, 그 IllegalArgumentException 메시지는
        // <b>주소 원문을 통째로</b> 담는다 — 그 메시지가 처리 이력 컬럼에 그대로 영속됐다.
        String addr = "http://ts02 secret internal:9500";
        VlmClient client = plainClient();

        assertThatThrownBy(() -> client.submitDescribe(req("req-bad2"), addr)
                .block(Duration.ofSeconds(5)))
                .isInstanceOf(NonRetryableExternalException.class)
                .hasMessageNotContaining(addr)
                .hasMessageNotContaining("ts02")
                .hasMessageNotContaining("9500");
    }

    /**
     * 반대 방향도 함께 고정한다 — 장비를 고르지 <b>않은</b> 요청에는 override 가 그대로 적용된다
     * (R11 「설정을 바꾸면 다음 호출이 새 주소로 나간다」가 이 변경으로 깨지지 않았다).
     */
    @Test
    @DisplayName("장비를_고르지_않은_요청에는_override_가_그대로_적용된다")
    void 장비를_고르지_않은_요청에는_override_가_그대로_적용된다() throws Exception {
        MockWebServer overridden = new MockWebServer();
        overridden.start();
        try {
            SystemConfigService configService = mock(SystemConfigService.class);
            when(configService.findString(anyString()))
                    .thenReturn(Optional.of(overridden.url("/").toString()));
            @SuppressWarnings("unchecked")
            ObjectProvider<SystemConfigService> provider = mock(ObjectProvider.class);
            when(provider.getIfAvailable()).thenReturn(configService);

            WebClient webClient = new WebClientConfig().vlmWebClient(
                    bootDefault.url("/").toString(), "", new VlmUrlPolicy(),
                    new IntegrationEndpointResolver(provider));
            overridden.enqueue(accepted("req-override"));

            // when — 고른 장비 없음.
            client(webClient).submitDescribe(req("req-override")).block(Duration.ofSeconds(5));

            // then
            RecordedRequest received = overridden.takeRequest(5, TimeUnit.SECONDS);
            assertThat(received).isNotNull();
            assertThat(received.getPath()).isEqualTo(VlmClient.DESCRIBE_PATH);
        } finally {
            overridden.shutdown();
        }
    }
}
