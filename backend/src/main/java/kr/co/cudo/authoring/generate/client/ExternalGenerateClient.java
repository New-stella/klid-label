package kr.co.cudo.authoring.generate.client;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.github.resilience4j.reactor.retry.RetryOperator;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import kr.co.cudo.authoring.generate.dto.BackgroundGenerateRequest;
import kr.co.cudo.authoring.generate.dto.BackgroundGenerateResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Phase 9 — 외부 SFR-06/11 생성 시스템 호출 클라이언트.
 *
 * <p>V1.5 정책: 본 저작도구는 외부 시스템에 배경영상 생성을 요청만 하고, 결과 산출은 외부 책임.
 * 외부 시스템 미연결 시(현재 단계) {@code authoring.generate.enabled=false} 로 mock 응답을 반환한다.
 *
 * <p>SSRF 방어: base-url 은 application.yml 에서만 주입 (사용자 입력 미반영).
 */
@Slf4j
@Component
public class ExternalGenerateClient {

    private final WebClient webClient;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;
    private final boolean enabled;

    public ExternalGenerateClient(
            @Value("${authoring.generate.base-url:http://localhost:9400}") String baseUrl,
            @Value("${authoring.generate.enabled:false}") boolean enabled,
            CircuitBreakerRegistry cbRegistry,
            RetryRegistry retryRegistry) {
        this.webClient = WebClient.builder().baseUrl(baseUrl).build();
        this.circuitBreaker = cbRegistry.circuitBreaker("generate");
        this.retry = retryRegistry.retry("generate");
        this.enabled = enabled;
    }

    /**
     * 외부 시스템에 배경영상 생성 요청. enabled=false 인 경우 mock 응답 반환.
     */
    public Mono<BackgroundGenerateResponse> request(BackgroundGenerateRequest req) {
        if (!enabled) {
            // 외부 미연결 — mock 응답 (요청 접수만 ack)
            BackgroundGenerateResponse mock = new BackgroundGenerateResponse(
                    "MOCK-" + UUID.randomUUID(),
                    req.genType(),
                    "ACCEPTED",
                    LocalDateTime.now()
            );
            log.info("[Generate] external disabled → mock ack genType={}", req.genType());
            return Mono.just(mock);
        }
        return webClient.post()
                .uri("/api/v1/background/request")
                .bodyValue(req)
                .retrieve()
                .bodyToMono(BackgroundGenerateResponse.class)
                .timeout(Duration.ofSeconds(60))
                .transformDeferred(RetryOperator.of(retry))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }
}
