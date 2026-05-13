package kr.co.cudo.authoring.common.client;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.github.resilience4j.reactor.retry.RetryOperator;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import kr.co.cudo.authoring.common.client.dto.Sam2Request;
import kr.co.cudo.authoring.common.client.dto.Sam2Response;
import kr.co.cudo.authoring.common.client.dto.Sam2TrackRequest;
import kr.co.cudo.authoring.common.client.dto.Sam2TrackResponse;
import kr.co.cudo.authoring.common.client.dto.VlmVerifyRequest;
import kr.co.cudo.authoring.common.client.dto.VlmVerifyResponse;
import kr.co.cudo.authoring.common.client.dto.YoloRequest;
import kr.co.cudo.authoring.common.client.dto.YoloResponse;
import kr.co.cudo.authoring.common.client.dto.YoloTrackRequest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

@Component
public class AiServerClient {

    private final WebClient webClient;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;

    public AiServerClient(@Qualifier("aiServerWebClient") WebClient webClient,
                          @Qualifier("aiCircuitBreaker") CircuitBreaker circuitBreaker,
                          RetryRegistry retryRegistry) {
        this.webClient = webClient;
        this.circuitBreaker = circuitBreaker;
        this.retry = retryRegistry.retry("ai");
    }

    public Mono<YoloResponse> predictYolo(YoloRequest request) {
        return webClient.post()
                .uri("/infer/yolo/predict")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(YoloResponse.class)
                .timeout(Duration.ofSeconds(60))
                .transformDeferred(RetryOperator.of(retry))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }

    /**
     * YOLO Track 추론 — Phase 3.
     * <p>{@code clip_id} 단위로 ai-server 가 트래커 상태를 격리하며, 동일 객체에 동일 track_id 를 부여한다.
     * Phase 4 에서 {@link kr.co.cudo.authoring.batch.step.YoloAutolabelStep} 가 {@link #predictYolo}
     * 대신 본 메서드를 호출하도록 전환된다.
     */
    public Mono<YoloResponse> predictYoloTrack(YoloTrackRequest request) {
        return webClient.post()
                .uri("/infer/yolo/track")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(YoloResponse.class)
                .timeout(Duration.ofSeconds(60))
                .transformDeferred(RetryOperator.of(retry))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }

    public Mono<Sam2Response> segment(Sam2Request request) {
        return webClient.post()
                .uri("/infer/sam2/segment")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(Sam2Response.class)
                .timeout(Duration.ofSeconds(60))
                .transformDeferred(RetryOperator.of(retry))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }

    public Mono<Sam2TrackResponse> track(Sam2TrackRequest request) {
        return webClient.post()
                .uri("/infer/sam2/track")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(Sam2TrackResponse.class)
                .timeout(Duration.ofSeconds(60))
                .transformDeferred(RetryOperator.of(retry))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }

    public Mono<VlmVerifyResponse> verifyObjects(VlmVerifyRequest request) {
        return webClient.post()
                .uri("/infer/vlm/verify-objects")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(VlmVerifyResponse.class)
                .timeout(Duration.ofSeconds(60))
                .transformDeferred(RetryOperator.of(retry))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }
}
