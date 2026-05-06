package kr.co.cudo.authoring.common.client;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import kr.co.cudo.authoring.common.client.dto.DeidentifyRequest;
import kr.co.cudo.authoring.common.client.dto.DeidentifyResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

@Component
public class DeidentifyClient {

    private final WebClient webClient;
    private final CircuitBreaker circuitBreaker;

    public DeidentifyClient(@Qualifier("deidentifyWebClient") WebClient webClient,
                            @Qualifier("deidCircuitBreaker") CircuitBreaker circuitBreaker) {
        this.webClient = webClient;
        this.circuitBreaker = circuitBreaker;
    }

    public Mono<DeidentifyResponse> deidentify(DeidentifyRequest request) {
        return webClient.post()
                .uri("/api/v1/deidentify")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(DeidentifyResponse.class)
                .timeout(Duration.ofSeconds(60))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }
}
