package kr.co.cudo.authoring.common.client;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.github.resilience4j.reactor.retry.RetryOperator;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import kr.co.cudo.authoring.common.client.dto.DeidentifyRequest;
import kr.co.cudo.authoring.common.client.dto.DeidentifyResponse;
import kr.co.cudo.authoring.webhook.idempotency.LsWebhookIdempotency;
import kr.co.cudo.authoring.webhook.idempotency.WebhookIdempotencyLedger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.UUID;

/**
 * Deidentify 외부 시스템 위탁 클라이언트 — Phase 3 보강.
 *
 * <p>ccarch {@code if-deidentify-spi} (Outbound 비동기 위탁) 인터페이스 구현체.
 *
 * <h3>Phase 3 변경점</h3>
 * <ul>
 *   <li><b>idempotencyKey 발급</b>: 호출자가 {@code DeidentifyRequest.idempotencyKey} 를 null/blank 로
 *       전달하면 UUID 로 자동 발급. 외부 호출 직전 {@code X-Idempotency-Key} 헤더 + 페이로드에 동일 키 포함.
 *       (VlmClient 와 동일 패턴 — Phase 1 멱등 키 단일 출처 원칙)</li>
 *   <li><b>Allowlist 사전 기록</b>: 외부 위탁 직전
 *       {@link WebhookIdempotencyLedger#recordIssued(String, String)}
 *       (channel={@code DEIDENTIFY}) 로 사전 발급 기록. Phase 2 의 결과 webhook 수신 시 allowlist 검증에 사용.</li>
 *   <li><b>Retry 재구독</b>: Resilience4j Retry 는 동일 Mono 를 재구독하므로 재시도 시 같은 키가
 *       헤더/페이로드 양쪽에 그대로 유지된다 (재발급 없음).</li>
 * </ul>
 *
 * <h3>보안</h3>
 * <ul>
 *   <li>SSRF (CWE-918): base-url 은 application.yml. 사용자 입력 X.</li>
 *   <li>Privacy (CWE-359): 본 클라이언트는 idempotencyKey(UUID, PII 아님) 만 로그 출력.
 *       소스/타깃 경로는 호출자 책임 — 기존 동작 유지.</li>
 * </ul>
 */
@Slf4j
@Component
public class DeidentifyClient {

    /** 멱등 헤더 — 외부 시스템이 동일 키 재인계 시 중복 처리 회피. */
    public static final String IDEMPOTENCY_HEADER = "X-Idempotency-Key";

    /** ccarch SPI 경로. */
    public static final String SUBMIT_PATH = "/api/v1/deidentify";

    private final WebClient webClient;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;
    /** Phase 3 신설 — 발급 사전 기록용. null 허용(테스트 호환). */
    @Nullable
    private final WebhookIdempotencyLedger idempotencyLedger;

    /**
     * Phase 0 호환 생성자 — ledger 미주입. recordIssued 호출은 스킵된다.
     * 기존 테스트 호환용. 운영 빈은 {@link #DeidentifyClient(WebClient, CircuitBreaker, RetryRegistry, WebhookIdempotencyLedger)} 가 등록된다.
     */
    public DeidentifyClient(@Qualifier("deidentifyWebClient") WebClient webClient,
                            @Qualifier("deidCircuitBreaker") CircuitBreaker circuitBreaker,
                            RetryRegistry retryRegistry) {
        this(webClient, circuitBreaker, retryRegistry, null);
    }

    /** Phase 3 운영 생성자 — Spring 이 우선 매칭. */
    @Autowired
    public DeidentifyClient(@Qualifier("deidentifyWebClient") WebClient webClient,
                            @Qualifier("deidCircuitBreaker") CircuitBreaker circuitBreaker,
                            RetryRegistry retryRegistry,
                            @Nullable WebhookIdempotencyLedger idempotencyLedger) {
        this.webClient = webClient;
        this.circuitBreaker = circuitBreaker;
        this.retry = retryRegistry.retry("deid");
        this.idempotencyLedger = idempotencyLedger;
    }

    /**
     * 위탁 호출 — Phase 3 보강.
     *
     * <p>idempotencyKey 가 request 에 비어있으면 UUID 자동 발급. 외부 호출 직전
     * ledger.recordIssued(key, "DEIDENTIFY") 로 allowlist 사전 기록.
     */
    public Mono<DeidentifyResponse> deidentify(DeidentifyRequest request) {
        String idemKey = resolveIdempotencyKey(request.idempotencyKey());

        // 사전 기록 — Phase 2 webhook allowlist 검증 위함. channel=DEIDENTIFY 명시.
        if (idempotencyLedger != null) {
            try {
                idempotencyLedger.recordIssued(idemKey, LsWebhookIdempotency.CHANNEL_DEIDENTIFY, null);
            } catch (RuntimeException e) {
                // 사전 기록 실패는 위탁 자체를 막지 않음 — webhook 인계 시 unknown 키로 거부될 뿐.
                log.warn("[Deid] recordIssued failed (continue) reason={}", e.getClass().getSimpleName());
            }
        }

        DeidentifyRequest enriched = new DeidentifyRequest(
                request.sourcePath(), request.targetPath(), idemKey);

        return webClient.post()
                .uri(SUBMIT_PATH)
                .header(IDEMPOTENCY_HEADER, idemKey)
                .bodyValue(enriched)
                .retrieve()
                .bodyToMono(DeidentifyResponse.class)
                .timeout(Duration.ofSeconds(60))
                .transformDeferred(RetryOperator.of(retry))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }

    /** 호출자가 null/blank 를 주면 UUID 발급. Mono 외부에서 1회만 발급되므로 retry 시 동일 키 유지. */
    private String resolveIdempotencyKey(String provided) {
        if (provided == null || provided.isBlank()) {
            return UUID.randomUUID().toString();
        }
        return provided;
    }
}
