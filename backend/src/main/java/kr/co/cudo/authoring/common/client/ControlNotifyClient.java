package kr.co.cudo.authoring.common.client;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.github.resilience4j.reactor.retry.RetryOperator;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import kr.co.cudo.authoring.common.client.dto.ControlNotifyRequest;
import kr.co.cudo.authoring.controlnotify.dto.TaskCompletedPayload;
import kr.co.cudo.authoring.controlnotify.dto.TaskModifiedPayload;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Phase 2 — 관제서버 outbound 완료/수정 통지 클라이언트.
 *
 * <p>보안:
 * <ul>
 *   <li>SSRF (CWE-918): base-url 은 application.yml 설정값 — 사용자 입력 X.</li>
 *   <li>Privacy (CWE-359): 페이로드에 PII, 토큰, 원본 이미지 경로 포함 금지.</li>
 * </ul>
 *
 * <p>{@code authoring.control-notify.enabled=false} (기본값) 이면 빈 등록 안 됨.
 */
@Component
@ConditionalOnProperty(name = "authoring.control-notify.enabled", havingValue = "true")
public class ControlNotifyClient {

    /** Reactor 호출 자체 timeout (각 Mono 에 적용). */
    public static final Duration CALL_TIMEOUT = Duration.ofSeconds(10);

    /**
     * 호출자가 .block() 사용 시 적용할 권장 timeout.
     * CALL_TIMEOUT(10s) + 마진 5s = 15s.
     */
    public static final Duration BLOCK_TIMEOUT = Duration.ofSeconds(15);

    private static final String NOTIFY_PATH = "/api/v1/notify";

    private final WebClient webClient;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;

    public ControlNotifyClient(@Qualifier("controlNotifyWebClient") WebClient webClient,
                               @Qualifier("controlNotifyCircuitBreaker") CircuitBreaker circuitBreaker,
                               RetryRegistry retryRegistry) {
        this.webClient = webClient;
        this.circuitBreaker = circuitBreaker;
        this.retry = retryRegistry.retry("controlNotify");
    }

    /**
     * TASK_COMPLETED 통지 전송.
     */
    public Mono<Void> sendTaskCompleted(TaskCompletedPayload payload) {
        ControlNotifyRequest request = new ControlNotifyRequest("TASK_COMPLETED", payload);
        return doPost(request);
    }

    /**
     * TASK_MODIFIED 통지 전송.
     */
    public Mono<Void> sendTaskModified(TaskModifiedPayload payload) {
        ControlNotifyRequest request = new ControlNotifyRequest("TASK_MODIFIED", payload);
        return doPost(request);
    }

    private Mono<Void> doPost(ControlNotifyRequest request) {
        return webClient.post()
                .uri(NOTIFY_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .toBodilessEntity()
                .then()
                .timeout(CALL_TIMEOUT)
                .transformDeferred(RetryOperator.of(retry))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }
}
