package kr.co.cudo.authoring.common.client;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.github.resilience4j.reactor.retry.RetryOperator;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
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
 * 관제지원시스템 outbound 완료/수정 통지 클라이언트.
 *
 * <p><b>관제 계약(API-251 / API-285)</b>: 통지 종류별로 경로가 다르다.
 * <ul>
 *   <li>완료 — {@code POST {base}/api/data-set/v2/jobs/{job_id}/notify-completed}</li>
 *   <li>수정 — {@code POST {base}/api/data-set/v2/jobs/{job_id}/notify-updated}</li>
 * </ul>
 * 구 단일 경로 {@code /api/v1/notify} 와 {@code {eventType, payload}} 2단 중첩 바디는 폐기됐다.
 *
 * <p><b>4xx 상태코드 보존</b>: {@code .toBodilessEntity()} 는 상태코드·바디를 잃어 409/404 자기치유
 * 분기를 불가능하게 하므로, {@code exchangeToMono} 로 상태코드와 바디를 함께 캡처해
 * {@link ControlNotifyStatusException} 으로 던진다(S4·S5).
 *
 * <p>보안:
 * <ul>
 *   <li>SSRF (CWE-918): base-url 은 application.yml 설정값 — 사용자 입력 X. job_id 는 내부 PK 문자열.</li>
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

    /** 완료 통지 경로 템플릿 (관제 API-251). */
    public static final String COMPLETED_PATH = "/api/data-set/v2/jobs/{jobId}/notify-completed";

    /** 수정 통지 경로 템플릿 (관제 API-285). */
    public static final String UPDATED_PATH = "/api/data-set/v2/jobs/{jobId}/notify-updated";

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
     * 완료 통지 전송 — {@code notify-completed}.
     *
     * @throws ControlNotifyStatusException 4xx 응답 시(409 = 이미 등록된 job_id)
     */
    public Mono<Void> sendTaskCompleted(TaskCompletedPayload payload) {
        return doPost(COMPLETED_PATH, payload.jobId(), payload);
    }

    /**
     * 수정 통지 전송 — {@code notify-updated}.
     *
     * @throws ControlNotifyStatusException 4xx 응답 시(404 = 선행 완료 통지 없음)
     */
    public Mono<Void> sendTaskModified(TaskModifiedPayload payload) {
        return doPost(UPDATED_PATH, payload.jobId(), payload);
    }

    private Mono<Void> doPost(String pathTemplate, String jobId, Object body) {
        return webClient.post()
                .uri(uriBuilder -> uriBuilder.path(pathTemplate).build(jobId))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchangeToMono(response -> {
                    if (response.statusCode().is2xxSuccessful()) {
                        // 2xx(200/201/202 모두 수용) — 바디는 사용하지 않으나 커넥션 반환을 위해 소비한다.
                        return response.releaseBody().then();
                    }
                    int status = response.statusCode().value();
                    return response.bodyToMono(String.class)
                            .defaultIfEmpty("")
                            .flatMap(errorBody -> Mono.error(toException(status, errorBody)));
                })
                .timeout(CALL_TIMEOUT)
                .transformDeferred(RetryOperator.of(retry))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }

    /**
     * 4xx 는 결정적 실패({@link ControlNotifyStatusException}) — 재시도·서킷 집계 제외 대상이나
     * 예외 자체는 호출자까지 전파되어 자기치유(409→updated / 404→completed) 분기를 발동한다.
     * 5xx 는 일시적 실패로 남겨 재시도·서킷 집계 대상이 되게 한다.
     */
    private RuntimeException toException(int status, String errorBody) {
        if (status >= 400 && status < 500) {
            return new ControlNotifyStatusException(status, errorBody);
        }
        return new IllegalStateException("관제 통지 실패 status=" + status);
    }
}
