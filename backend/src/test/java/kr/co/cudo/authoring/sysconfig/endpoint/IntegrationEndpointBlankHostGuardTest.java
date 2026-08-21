package kr.co.cudo.authoring.sysconfig.endpoint;

import kr.co.cudo.authoring.common.client.NonRetryableExternalException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ★ <b>미연동(연동 주소 미주입)이면 아무 데도 보내지 않는다</b> 를 실제로 성립시키는 가드.
 *
 * <h3>무엇이 어긋나 있었나 (실측)</h3>
 * <p>주소가 비면 {@code WebClient.baseUrl("")} + 상대 URI 가 되는데, 그러면 요청이 <b>보내지지 않는
 * 것이 아니라 loopback 의 80 포트로 나간다</b>({@code Connection refused: /[0:0:0:0:0:0:0:1]:80} 로
 * 관측). 온프렘은 같은 호스트에 프론트 웹서버를 두므로 80 이 열려 있으면 <b>연결이 수신되고 접근
 * 로그에 요청 경로가 남는다</b>. 위탁 바디에는 비식별 영상의 절대 경로와 콜백 주소가 실린다.
 */
class IntegrationEndpointBlankHostGuardTest {

    /** 호출 횟수를 세는 다음 단계 — 전송이 실제로 일어났는지 판정한다. */
    private static final class CountingExchange implements ExchangeFunction {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public Mono<ClientResponse> exchange(ClientRequest request) {
            calls.incrementAndGet();
            return Mono.just(ClientResponse.create(org.springframework.http.HttpStatus.OK).build());
        }
    }

    private static ClientRequest request(String uri) {
        return ClientRequest.create(HttpMethod.POST, URI.create(uri)).build();
    }

    @Test
    @DisplayName("★★주소가_비어_상대URI가_되면_전송을_하지_않고_실패시킨다_loopback80_유출차단")
    void relativeUriIsNotSent() {
        ExchangeFilterFunction guard =
                IntegrationEndpointTransportGuards.requireResolvedHost(IntegrationEndpoint.VLM);
        CountingExchange next = new CountingExchange();

        // baseUrl 이 "" 이면 요청 URL 은 호스트 없는 상대 URI 가 된다(실측 형상).
        StepVerifier.create(guard.filter(request("/v1/videovlm/verify"), next))
                .expectError(NonRetryableExternalException.class)
                .verify();

        assertThat(next.calls.get())
                .as("전송 시도가 한 번이라도 있으면 loopback:80 으로 나가 접근 로그에 요청 경로가 남는다")
                .isZero();
    }

    @Test
    @DisplayName("★거부_사유에_주소도_경로도_토큰도_싣지_않는다")
    void failureMessageLeaksNothing() {
        ExchangeFilterFunction guard =
                IntegrationEndpointTransportGuards.requireResolvedHost(IntegrationEndpoint.VLM);

        StepVerifier.create(guard.filter(request("/v1/videovlm/verify"), new CountingExchange()))
                .expectErrorSatisfies(e -> assertThat(e.getMessage())
                        .doesNotContain("/v1/videovlm/verify")
                        .doesNotContain("localhost")
                        .doesNotContain("127.0.0.1"))
                .verify();
    }

    /**
     * ★ 재시도·서킷 집계에서 제외돼야 한다 — 주소가 없다는 것은 재전송해도 결과가 같은 결정적 실패다.
     *
     * <p>{@code NonRetryableExternalException} 은 두 축의 {@code ignore-exceptions} 에 이미 등록돼
     * 있으므로, 이 타입을 바꾸면 미연동 상태가 지수 백오프 지연과 서킷 오픈을 유발한다.
     */
    @Test
    @DisplayName("실패는_비재시도_유형이라_재시도와_서킷집계에서_제외된다")
    void failureIsNonRetryable() {
        ExchangeFilterFunction guard =
                IntegrationEndpointTransportGuards.requireResolvedHost(IntegrationEndpoint.VLM);

        StepVerifier.create(guard.filter(request("/v1/videovlm/verify"), new CountingExchange()))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(NonRetryableExternalException.class))
                .verify();
    }

    @Test
    @DisplayName("주소가_해석되면_종전대로_그대로_전송한다_정상경로_무변경")
    void resolvedHostPassesThrough() {
        ExchangeFilterFunction guard =
                IntegrationEndpointTransportGuards.requireResolvedHost(IntegrationEndpoint.VLM);
        CountingExchange next = new CountingExchange();

        StepVerifier.create(guard.filter(request("https://vendor.example.net/v1/videovlm/verify"), next))
                .expectNextCount(1)
                .verifyComplete();

        assertThat(next.calls.get()).isEqualTo(1);
    }
}
