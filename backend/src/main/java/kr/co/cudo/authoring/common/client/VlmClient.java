package kr.co.cudo.authoring.common.client;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.github.resilience4j.reactor.retry.RetryOperator;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import kr.co.cudo.authoring.common.client.dto.VlmTimeseriesRequest;
import kr.co.cudo.authoring.common.client.dto.VlmTimeseriesResponse;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 외부 VLM <b>verify</b> 위탁 클라이언트 — 벤더 확정 계약(IntelliVIX Video VLM API v2.0.1) 정합.
 *
 * <p>{@code POST /v1/videovlm/verify} 로 시계열 메타 분석을 비동기 위탁하고, 동기 응답으로
 * 수락({@code status="accepted"}) 여부만 확인한다. 실제 결과는 {@code POST /v1/vlm/callback} 콜백으로 수신한다.
 *
 * <p><b>구 규격(describe)에서 전환</b> — 요청 바디에 {@code event_type}(검증 대상 이벤트 유형)이
 * 추가됐고 경로가 {@code /verify} 로 바뀌었다. 동기 응답 형식({@code request_id} echo +
 * {@code status="accepted"})은 describe 와 동일하므로 검증 로직은 그대로다.
 *
 * <h3>설계 원칙</h3>
 * <ul>
 *   <li><b>비동기 위탁</b>: 동기 응답은 "수락" 만 확인. 결과는 콜백.</li>
 *   <li><b>상관관계</b>: 상관키는 {@code request_id}(요청 바디). verify 응답에 externalJobId 는 없다.
 *       호출자(Step)가 request_id 를 발급/주입하며, 응답의 request_id echo 일치를 본 클라이언트가 검증한다.
 *       (Step 이 request_id 를 ledger(request_id→rawSn)에 먼저 등록해 콜백 역조회를 성립시킨다.)</li>
 *   <li><b>토글</b>: {@code vlm.client.enabled=false}(기본) 일 때 외부 호출 없이 즉시
 *       {@link VlmTimeseriesResponse#skipped(String)} 반환 — local/dev 영향 0건.</li>
 *   <li><b>회복성</b>: Resilience4j Retry(exp backoff) + CircuitBreaker. 타임아웃은 WebClient
 *       {@code .timeout(...)} 으로 Reactor 네이티브 처리(단일 출처).</li>
 *   <li><b>보안</b>: URL/토큰 하드코딩 금지(환경변수). URL SSRF/HTTPS 검증은 {@code WebClientConfig}.
 *       응답 request_id echo + status 화이트리스트 검증. 로그 출력 전 {@link #safeForLog(String)}
 *       로 CRLF/탭 sanitize(CWE-117).</li>
 * </ul>
 */
@Slf4j
@Component
public class VlmClient {

    /** 외부 위탁 요청 경로 — 벤더 확정 계약(v2.0.1) verify 엔드포인트. */
    static final String VERIFY_PATH = "/v1/videovlm/verify";

    /** 수락 상태 화이트리스트 — verify 동기 응답은 "accepted" 만 정상(CWE-20). */
    private static final String STATUS_ACCEPTED = VlmTimeseriesResponse.STATUS_ACCEPTED;

    /** 로그 sanitize 패턴 — CR/LF/TAB 제거(CWE-117 Log Injection 차단). */
    private static final Pattern LOG_UNSAFE_CHARS = Pattern.compile("[\\r\\n\\t]");

    private final WebClient webClient;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;
    private final boolean enabled;
    private final Duration timeout;

    public VlmClient(@Qualifier("vlmWebClient") WebClient webClient,
                     CircuitBreakerRegistry circuitBreakerRegistry,
                     RetryRegistry retryRegistry,
                     @Value("${vlm.client.enabled:false}") boolean enabled,
                     @Value("${vlm.client.timeout-seconds:10}") long timeoutSeconds) {
        this.webClient = webClient;
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker("vlmClient");
        this.retry = retryRegistry.retry("vlmClient");
        this.enabled = enabled;
        this.timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
    }

    /**
     * 시계열 메타 분석을 외부 VLM verify 로 비동기 위탁한다 (@req R1).
     *
     * <p>enabled=false 면 외부 호출 없이 즉시 SKIPPED 응답을 반환한다(NO-OP).
     * enabled=true 면 Retry + CircuitBreaker + 응답 무결성 검증이 적용된 외부 호출을 수행한다.
     *
     * <p>{@code event_type} 허용목록 판정은 <b>호출자(위탁 단계)</b>가 소유한다 — 본 클라이언트는
     * 전달받은 값을 그대로 실어 보낸다(판정 지점을 두 곳으로 늘리지 않는다).
     *
     * @param request verify 요청 바디. {@code request_id} 가 null/blank 이면 방어적으로 UUID 자동 발급.
     * @return 외부 시스템 수락 응답 (request_id echo + status)
     */
    public Mono<VlmTimeseriesResponse> submitTimeseries(VlmTimeseriesRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        String requestId = resolveRequestId(request.requestId());
        VlmTimeseriesRequest enriched = new VlmTimeseriesRequest(
                requestId, request.eventType(), request.media(), request.callbackUrl());

        if (!enabled) {
            log.info("[Vlm] verify skipped (disabled) request_id={}", safeForLog(requestId));
            return Mono.just(VlmTimeseriesResponse.skipped(requestId));
        }

        log.info("[Vlm] verify submit request_id={}", safeForLog(requestId));
        return webClient.post()
                .uri(VERIFY_PATH)
                .bodyValue(enriched)
                .retrieve()
                // V1: 4xx(특히 400 형식오류·422 파라미터 값 오류)는 벤더 규격(§4.1)상 비-일시적 오류다.
                // 비재시도 예외로 분류해 재시도·서킷집계에서 제외한다(5xx·네트워크만 재시도). happy path/5xx 무변경.
                .onStatus(HttpStatusCode::is4xxClientError, this::toNonRetryable4xx)
                .bodyToMono(VlmTimeseriesResponse.class)
                .timeout(timeout)
                .map(resp -> validateResponse(resp, requestId))
                .transformDeferred(RetryOperator.of(retry))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }

    /**
     * 4xx 클라이언트 오류를 비재시도 예외로 변환 — {@code .retrieve().onStatus(...)} 훅용(V1).
     *
     * <p>본문을 소비/해제(리소스 누수 방지)한 뒤 상태 코드만 기록해 {@link NonRetryableExternalException}
     * 으로 전파한다. Resilience4j retry/circuitbreaker 는 {@code ignore-exceptions} 로 이를 건너뛴다.
     * CWE-209: 외부 응답 본문 원문은 예외/로그에 노출하지 않는다(상태 코드만).
     */
    private Mono<Throwable> toNonRetryable4xx(ClientResponse response) {
        int status = response.statusCode().value();
        log.warn("[Vlm] verify non-retryable 4xx status={}", status);
        return response.releaseBody()
                .then(Mono.error(new NonRetryableExternalException(
                        "VLM verify 4xx 응답(status=" + status + ")")));
    }

    /**
     * 호출자가 request_id 를 제공하지 않으면 UUIDv4 로 방어적 발급.
     *
     * <p>정상 흐름에서는 Step 이 request_id 를 발급/등록하므로 여기서는 주입값을 그대로 사용한다.
     * Resilience4j Retry 는 동일 Mono 를 재구독하므로 재시도 시 같은 request_id 가 바디에 유지된다.
     */
    private String resolveRequestId(String provided) {
        if (provided == null || provided.isBlank()) {
            return UUID.randomUUID().toString();
        }
        return provided;
    }

    /**
     * verify 동기 응답 무결성 검증 — 벤더 규격 정합(describe 와 동일 형식).
     *
     * <p>외부 시스템은 신뢰 영역 밖이므로 응답을 검증한다:
     * <ol>
     *   <li>request_id echo 일치 — 상관관계 위조/혼선 차단.</li>
     *   <li>status == "accepted" — 수락 화이트리스트(CWE-20).</li>
     * </ol>
     * 검증 실패 시 {@link CustomException}{@code (EXTERNAL_API_ERROR)} 로 변환되어 Retry/FAILED 경로로 흐른다.
     *
     * @return 검증을 통과한 응답 그대로 반환
     */
    private VlmTimeseriesResponse validateResponse(VlmTimeseriesResponse resp, String expectedRequestId) {
        if (resp == null) {
            throw new CustomException(ErrorCode.EXTERNAL_API_ERROR, "VLM verify 응답이 null 입니다.");
        }
        String echoed = resp.requestId();
        if (echoed == null || !echoed.equals(expectedRequestId)) {
            throw new CustomException(ErrorCode.EXTERNAL_API_ERROR,
                    "VLM verify 응답 request_id echo 가 일치하지 않습니다: " + safeForLog(echoed));
        }
        String status = resp.status();
        if (!STATUS_ACCEPTED.equals(status)) {
            throw new CustomException(ErrorCode.EXTERNAL_API_ERROR,
                    "VLM verify 응답 status 가 accepted 가 아닙니다: " + safeForLog(status));
        }
        return resp;
    }

    /**
     * 로그 출력용 외부 입력 sanitize — CWE-117 Log Injection 차단.
     *
     * <p>외부 응답 값(request_id echo, status) 을 로그에 출력하기 전 호출. CR/LF/TAB 을 {@code _} 로 치환한다.
     */
    public static String safeForLog(String s) {
        if (s == null) return "null";
        return LOG_UNSAFE_CHARS.matcher(s).replaceAll("_");
    }

    /** 토글 상태 노출 — Step 측에서 enabled 분기 시 사용. */
    public boolean isEnabled() {
        return enabled;
    }
}
