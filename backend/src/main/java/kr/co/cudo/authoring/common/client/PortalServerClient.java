package kr.co.cudo.authoring.common.client;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.github.resilience4j.reactor.retry.RetryOperator;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;

@Component
public class PortalServerClient {

    private final WebClient webClient;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;
    private final String m2mToken;

    public PortalServerClient(@Qualifier("portalServerWebClient") WebClient webClient,
                              @Qualifier("portalCircuitBreaker") CircuitBreaker circuitBreaker,
                              RetryRegistry retryRegistry,
                              @Value("${authoring.integration.portal-server.m2m-token:}") String m2mToken) {
        this.webClient = webClient;
        this.circuitBreaker = circuitBreaker;
        this.retry = retryRegistry.retry("portal");
        this.m2mToken = m2mToken;
    }

    public Mono<Void> notifyUploadComplete(String uploadId, String userId) {
        return webClient.post()
                .uri("/api/v1/integration/uploads/{id}/complete", uploadId)
                .header("X-M2M-Token", m2mToken)
                .bodyValue(Map.of("userId", userId))
                .retrieve()
                .bodyToMono(Void.class)
                .timeout(Duration.ofSeconds(60))
                .transformDeferred(RetryOperator.of(retry))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }
}
