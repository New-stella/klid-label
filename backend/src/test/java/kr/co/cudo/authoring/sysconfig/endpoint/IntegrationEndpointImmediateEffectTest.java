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
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

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
 * ★ R11 핵심 회귀 가드 — <b>설정을 바꾸면 다음 호출이 실제로 새 주소로 나간다</b>.
 *
 * <h3>왜 이 테스트가 필요한가</h3>
 * <p>{@code WebClient} 는 {@code baseUrl} 을 <b>빈 생성 시점에</b> 고정한다. 그래서 설정만 바꾸고
 * 배선을 빠뜨리면 <b>저장은 되는데 호출 주소는 그대로</b>인, 이 저장소가 이미 겪은
 * "코드는 맞는데 실동작 0건" 결함이 된다. 화면·API·DB 테스트가 전부 통과해도 이 결함은 드러나지
 * 않으므로, <b>실제 소켓 두 개</b>를 띄워 어느 쪽이 요청을 받았는지로 판정한다.
 *
 * <p><b>mutation 확인</b>: {@code WebClientConfig} 에서 필터 배선(.filter(...))을 지우면 이 테스트는
 * "새 서버가 요청을 못 받았다"로 실패한다.
 *
 * <h3>대역(stub) 검증기가 필요 없어졌다</h3>
 * <p>구 정책은 내부망 대역을 차단해 loopback 에 뜨는 {@link MockWebServer} 로는 실제 전송을 확인할 수
 * 없었고, 그래서 판정을 통과시키는 대역(stub)이 필요했다. <b>대역 차단이 폐지</b>되어(2026-08-10
 * 사용자 확정) 이제 <b>진짜 리졸버 경로 그대로</b> 검증한다.
 */
class IntegrationEndpointImmediateEffectTest {

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

    /**
     * 설정에 값이 없는 상태(=행 없음, <b>정상 상태</b>)를 재현한다.
     *
     * <p>구 재현은 {@code getString} 이 예외를 던지는 형태였다. 지금 리졸버는
     * {@code findString} 으로 <b>부재를 값으로</b> 받는다 — 예외는 캐시되지 않아 정상 배포에서
     * 캐시가 영영 채워지지 않던 결함의 수정이다(MED-4).
     */
    private void noOverride() {
        when(systemConfigService.findString(anyString())).thenReturn(Optional.empty());
    }

    private void override(String url) {
        when(systemConfigService.findString(anyString())).thenReturn(Optional.of(url));
    }

    @Test
    @DisplayName("★설정을_바꾸면_재기동_없이_다음_호출이_새_주소로_나간다")
    void appliesNewAddressWithoutRestart() throws Exception {
        // given — 배포 기본값(구 주소)으로 만들어진 빈. 빈은 이 시점에 이미 고정돼 있다.
        String bootDefault = oldServer.url("/").toString();
        WebClient client = new WebClientConfig()
                .aiServerWebClient(bootDefault, resolver());
        oldServer.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));
        newServer.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));

        // when — 설정이 새 주소로 바뀐 뒤 호출한다(빈은 그대로다).
        override(newServer.url("/").toString());
        client.get().uri("/v1/detect").retrieve().bodyToMono(String.class)
                .block(Duration.ofSeconds(5));

        // then — 새 서버가 받았고 구 서버는 아무것도 받지 못했다.
        RecordedRequest received = newServer.takeRequest(5, TimeUnit.SECONDS);
        assertThat(received).as("새 주소가 요청을 받아야 한다").isNotNull();
        assertThat(received.getPath()).isEqualTo("/v1/detect");
        assertThat(oldServer.takeRequest(300, TimeUnit.MILLISECONDS))
                .as("구 주소로는 더 이상 나가지 않아야 한다").isNull();
    }

    @Test
    @DisplayName("설정이_없으면_배포_기본값_그대로_나간다 — 기존 형상 영향 0")
    void fallsBackToDeploymentDefault() throws Exception {
        // given
        String bootDefault = oldServer.url("/").toString();
        WebClient client = new WebClientConfig()
                .aiServerWebClient(bootDefault, resolver());
        oldServer.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));
        noOverride();

        // when
        client.get().uri("/v1/detect").retrieve().bodyToMono(String.class)
                .block(Duration.ofSeconds(5));

        // then
        RecordedRequest received = oldServer.takeRequest(5, TimeUnit.SECONDS);
        assertThat(received).isNotNull();
        assertThat(received.getPath()).isEqualTo("/v1/detect");
    }

    /**
     * ★ <b>기대값이 뒤집힌 케이스</b> — 구 동작은 전송 직전 재검증으로 내부망 주소를 <b>막았다</b>
     * (DNS rebinding 방어). 대역 차단이 폐지되어 재검증할 내용이 없어졌으므로 이제 <b>그대로 나간다</b>.
     *
     * <p>지우지 않고 뒤집는 이유 — "요청 직전에 막는 무언가가 있다"는 오해를 남기지 않기 위해서다.
     * 이 테스트가 통과하는 한 그런 게이트는 없다.
     */
    @Test
    @DisplayName("★전송_직전_대역_재검증을_하지_않는다 — 내부망_주소로도_나간다(구 차단 → 통과)")
    void doesNotRevalidateNetworkRangeBeforeSend() throws Exception {
        // given — 해석하면 loopback 인 호스트. 구 정책이라면 여기서 전송이 막혔다.
        String bootDefault = oldServer.url("/").toString();
        WebClient client = new WebClientConfig().aiServerWebClient(bootDefault, resolver());
        newServer.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));
        override("http://localhost:" + newServer.getPort());

        // when
        client.get().uri("/v1/detect").retrieve().bodyToMono(String.class)
                .block(Duration.ofSeconds(5));

        // then — 새 주소가 실제로 요청을 받았다.
        RecordedRequest received = newServer.takeRequest(5, TimeUnit.SECONDS);
        assertThat(received).isNotNull();
        assertThat(received.getPath()).isEqualTo("/v1/detect");
    }

    /**
     * ★ LOW-6 — base 경로 접두 판정이 <b>세그먼트 경계</b>를 본다.
     *
     * <p>구 동작은 {@code startsWith} 뿐이라 bootPath={@code /api} · 요청 {@code /apix/foo} 에서
     * {@code x/foo} 만 남았고, override 에 경로가 없으면 그 조각이 호스트 뒤에 그대로 붙어
     * <b>{@code newhostx} 라는 다른 호스트</b>로 요청이 나갔다. 현재 배포된 4종 기본값이 모두 경로
     * 없는 형태라 도달하지 않을 뿐, 기본값에 경로가 붙는 순간 활성화되는 결함이다.
     */
    @Test
    @DisplayName("★base_경로_접두는_세그먼트_경계로_판정한다 — 호스트가_변조되지_않는다")
    void basePathPrefixRespectsSegmentBoundary() {
        java.net.URI rewritten = IntegrationEndpointExchangeFilter.rewrite(
                java.net.URI.create("http://oldhost/apix/foo"), "http://oldhost/api", "http://newhost");

        assertThat(rewritten.getHost()).as("호스트가 붙어 늘어나면 안 된다").isEqualTo("newhost");
        assertThat(rewritten.getRawPath()).isEqualTo("/apix/foo");
    }

    @Test
    @DisplayName("base_경로_아래_요청은_정상적으로_접두가_떨어진다")
    void basePathIsStrippedForRealPrefix() {
        assertThat(IntegrationEndpointExchangeFilter.rewrite(
                java.net.URI.create("http://oldhost/api/foo"), "http://oldhost/api", "http://newhost/gw"))
                .isEqualTo(java.net.URI.create("http://newhost/gw/foo"));

        // base 경로와 정확히 같은 요청도 세그먼트가 합쳐지지 않아야 한다.
        assertThat(IntegrationEndpointExchangeFilter.rewrite(
                java.net.URI.create("http://oldhost/api"), "http://oldhost/api", "http://newhost/gw"))
                .isEqualTo(java.net.URI.create("http://newhost/gw"));
    }

    @Test
    @DisplayName("★새_주소에서_호스트를_못_뽑으면_재작성을_포기한다 — http://null_로_나가지_않는다")
    void abortsRewriteWhenAuthorityMissing() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        IntegrationEndpointExchangeFilter.rewrite(
                                java.net.URI.create("http://oldhost/v1/detect"), "http://oldhost", "http:///nohost"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("새_주소에_경로가_붙어_있으면_그_경로_아래로_요청이_나간다")
    void preservesBasePathOfNewAddress() throws Exception {
        // given
        String bootDefault = oldServer.url("/").toString();
        WebClient client = new WebClientConfig()
                .aiServerWebClient(bootDefault, resolver());
        newServer.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));
        override(newServer.url("/gateway").toString());

        // when
        client.get().uri("/v1/detect?top=5").retrieve().bodyToMono(String.class)
                .block(Duration.ofSeconds(5));

        // then — 새 base 의 경로가 앞에 붙고 쿼리는 보존된다.
        RecordedRequest received = newServer.takeRequest(5, TimeUnit.SECONDS);
        assertThat(received).isNotNull();
        assertThat(received.getPath()).isEqualTo("/gateway/v1/detect?top=5");
    }

    // ------------------------------------------------------------------------------------------
    // ★ 빈 배포 기본값 사슬 — 시계열(VLM) 연동만 배포 기본값이 빈 문자열이다.
    //
    //   baseUrl=""  →  요청이 상대 URI(/v1/videovlm/verify)
    //               →  IntegrationEndpointExchangeFilter 가 설정값으로 재작성 → 절대 URI
    //               →  IntegrationEndpointTransportGuards.requireResolvedHost 통과 → 전송
    //
    //   조각별 테스트는 있었으나 이 조합 전체를 잇는 테스트가 없어, "코드는 맞아 보이는데 실동작 0건"
    //   이 될 수 있는 자리였다. 아래 3건이 그 사슬을 고정한다.
    // ------------------------------------------------------------------------------------------

    /** 다음 단계로 넘어간 요청의 최종 URL 을 붙잡는다 — 재작성 결과를 실소켓 없이 정확히 판정한다. */
    private static final class CapturingExchange implements ExchangeFunction {
        private URI lastUrl;

        @Override
        public Mono<ClientResponse> exchange(ClientRequest request) {
            lastUrl = request.url();
            return Mono.just(ClientResponse.create(HttpStatus.OK).build());
        }
    }

    private static ClientRequest post(String uri) {
        return ClientRequest.create(HttpMethod.POST, URI.create(uri)).build();
    }

    /**
     * 운영 등가(엄격) 정책 — {@code prd} 프로파일 + 완화 플래그 off.
     *
     * <p>배포 기본값이 <b>비어 있으면 검증 자체를 생략</b>하므로(ADR-049 — 미연동 환경 기동 보장)
     * 이 인스턴스로도 빈이 만들어진다. 느슨한 정책을 쓰면 "정책이 관대해서 통과한 것"과 구분되지
     * 않으므로 일부러 엄격 쪽을 쓴다.
     */
    private static VlmUrlPolicy strictVlmUrlPolicy() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prd");
        return new VlmUrlPolicy(env, false);
    }

    /**
     * ★ 이것을 고정하는 이유 — 시계열은 배포 기본값이 빈 문자열이라 요청이 <b>상대 URI</b> 로 시작한다.
     * 재작성이 빈 기본값에서 동작하지 않으면(예: 기본값이 비면 조기 반환하도록 바뀌면) 운영자가 설정
     * 화면에 주소를 넣어도 요청은 상대 URI 그대로 남고, 뒤의 전송 가드에 막혀 <b>영영 연결되지 않는다</b>.
     */
    @Test
    @DisplayName("★배포_기본값이_비어도_설정_주소를_넣으면_그_주소의_절대URI로_나간다")
    void blankBootDefaultIsRewrittenToConfiguredAbsoluteUri() {
        // given — 배포 기본값 없음(시계열의 실제 형상) + 설정 화면에서 넣은 벤더 주소
        override("https://vendor.example.net");
        ExchangeFilterFunction rewrite =
                IntegrationEndpointExchangeFilter.of(IntegrationEndpoint.VLM, "", resolver());
        CapturingExchange next = new CapturingExchange();

        // when — 상대 URI 로 시작하는 요청(baseUrl 이 "" 일 때의 실측 형상)
        StepVerifier.create(rewrite.filter(post("/v1/videovlm/verify"), next))
                .expectNextCount(1)
                .verifyComplete();

        // then — 호스트가 붙은 절대 URI 가 되어야 전송 가드를 통과할 수 있다.
        assertThat(next.lastUrl.toString())
                .as("빈 기본값에서도 설정 주소로 재작성돼야 한다")
                .isEqualTo("https://vendor.example.net/v1/videovlm/verify");
    }

    /**
     * 대조군 — 설정도 비어 있으면 <b>재작성이 일어나지 않는다</b>는 것을 고정한다.
     *
     * <p>이 상태에서 전송을 실제로 막는 것은 {@code requireResolvedHost} 의 몫이며
     * ({@code IntegrationEndpointBlankHostGuardTest}), 여기서는 재작성 필터가 <b>없는 주소를 지어내지
     * 않는다</b>는 것만 본다 — 지어내면 요청이 엉뚱한 대상으로 나가고 가드도 통과해 버린다.
     */
    @Test
    @DisplayName("배포_기본값도_설정도_비면_재작성하지_않는다 — 상대URI_그대로")
    void blankBootDefaultWithoutOverrideStaysRelative() {
        // given
        noOverride();
        ExchangeFilterFunction rewrite =
                IntegrationEndpointExchangeFilter.of(IntegrationEndpoint.VLM, "", resolver());
        CapturingExchange next = new CapturingExchange();

        // when
        StepVerifier.create(rewrite.filter(post("/v1/videovlm/verify"), next))
                .expectNextCount(1)
                .verifyComplete();

        // then — 경로는 그대로이고 호스트는 생기지 않는다(전송 차단은 뒤의 가드가 담당).
        assertThat(next.lastUrl.toString()).isEqualTo("/v1/videovlm/verify");
        assertThat(next.lastUrl.getHost()).as("없는 호스트를 지어내면 안 된다").isNull();
    }

    /**
     * ★ <b>사슬 검증</b> — 재작성 필터와 전송 가드를 <b>실제 배선 순서 그대로</b> 이어 붙였을 때
     * 빈 기본값 + 설정 주소가 <b>전송까지 간다</b>.
     *
     * <p>필터를 손으로 조립하지 않고 <b>프로덕션 빈 {@code WebClientConfig#vlmWebClient} 를 그대로
     * 호출</b>한다 — 테스트가 자기만의 순서를 재현하면 배선이 뒤집혀도 알아채지 못한다.
     *
     * <h3>순서가 핵심이다</h3>
     * <p>가드({@code requireResolvedHost})를 재작성보다 <b>앞에</b> 두면, 아직 상대 URI 인 요청을 보고
     * "주소 미설정"으로 막아 버린다 — 설정에 주소를 넣어도 시계열 위탁이 영영 나가지 않는다. 반대로
     * 가드를 빼면 빈 주소일 때 loopback:80 으로 요청이 새어 나간다. 두 필터는 <b>이 순서로만</b>
     * 동시에 성립한다.
     *
     * <p><b>mutation 확인</b>: {@code WebClientConfig#vlmWebClient} 에서 재작성 필터와
     * {@code requireResolvedHost} 의 순서를 뒤집으면 이 테스트가 실패한다(요청이 서버에 도달하지 않는다).
     */
    @Test
    @DisplayName("★재작성_필터가_전송_가드보다_앞이라_빈_기본값에_설정_주소를_넣으면_실제로_전송된다")
    void rewriteRunsBeforeTransportGuardSoConfiguredAddressActuallySends() throws Exception {
        // given — 배포 기본값 없음(시계열 실제 형상) + 설정 화면에서 넣은 주소
        WebClient client = new WebClientConfig()
                .vlmWebClient("", "", strictVlmUrlPolicy(), resolver());
        newServer.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));
        override(newServer.url("/").toString());

        // when
        try {
            client.post().uri("/v1/videovlm/verify").retrieve().bodyToMono(String.class)
                    .block(Duration.ofSeconds(5));
        } catch (RuntimeException ignored) {
            // 전송 여부는 아래 소켓 관측으로만 판정한다 — 응답 처리 실패로 판정이 흐려지지 않게 한다.
        }

        // then — 설정 주소가 실제로 요청을 받았다.
        RecordedRequest received = newServer.takeRequest(5, TimeUnit.SECONDS);
        assertThat(received)
                .as("가드가 재작성보다 앞이면 상대 URI 단계에서 막혀 여기 도달하지 못한다")
                .isNotNull();
        assertThat(received.getPath()).isEqualTo("/v1/videovlm/verify");
    }
}
