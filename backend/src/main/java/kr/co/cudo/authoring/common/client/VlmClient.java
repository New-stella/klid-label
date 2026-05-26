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
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 외부 VLM 시계열 메타 분석 서비스 위탁 클라이언트 — Phase 1 신설.
 *
 * <p>ccarch {@code if-vlm-timeseries-spi} (Outbound 비동기 시계열 분석 위탁) 인터페이스
 * 구현체. 본 클라이언트는 영상 1건의 시계열 메타 분석을 외부 시스템에 비동기 위탁하고,
 * 외부가 발급한 작업 ID 를 받아 반환한다. 실제 결과는 Phase 2 의 결과 수신 webhook 으로 전달된다.
 *
 * <h3>설계 원칙</h3>
 * <ul>
 *   <li><b>비동기 위탁</b>: 본 클라이언트는 "요청 수락" 만 동기로 확인. 결과는 webhook.</li>
 *   <li><b>멱등성</b>: <b>idempotencyKey 발급 책임은 본 클라이언트가 단일화</b>한다.
 *       호출자가 null/blank 를 넘기면 UUID 로 자동 발급하고, 제공하면 그대로 사용한다.
 *       Resilience4j Retry 는 동일 Mono 를 재구독하므로 재시도 시 같은 키가 유지된다.</li>
 *   <li><b>토글</b>: {@code vlm.client.enabled=false} (기본값) 일 때 외부 호출 없이
 *       즉시 {@link VlmTimeseriesResponse#skipped(String)} 반환 — local/dev 환경 영향 0건.</li>
 *   <li><b>회복성</b>: Resilience4j Retry(max 3, exp backoff) + CircuitBreaker(50%/10) 적용.
 *       타임아웃은 WebClient {@code .timeout(...)} 으로 Reactor 네이티브 처리 — Resilience4j
 *       TimeLimiter 는 미사용 (DEV_FIX-1: 단일 출처 원칙).</li>
 *   <li><b>보안</b>: 외부 시스템 인증은 정적 Bearer 토큰({@code vlm.client.token}).
 *       URL/토큰은 코드에 하드코딩 금지 — 환경변수만 사용.
 *       URL SSRF/HTTPS 검증은 {@code WebClientConfig.validateExternalUrl} 에서 수행.
 *       외부 응답의 {@code externalJobId}/{@code status} 는 길이·패턴·화이트리스트로 검증.
 *       로그 출력 시 {@link #safeForLog(String)} 로 CRLF/탭 sanitize (CWE-117 Log Injection).</li>
 * </ul>
 */
@Slf4j
@Component
public class VlmClient {

    /** 외부 위탁 요청 본문 경로 — ccarch SPI 명세 기준. 외부 계약 확정 시 환경변수로 분리 가능. */
    static final String SUBMIT_PATH = "/v1/timeseries/submit";

    /** 멱등 헤더 — 외부 시스템이 동일 키 재인계 시 중복 처리 회피용. */
    static final String IDEMPOTENCY_HEADER = "X-Idempotency-Key";

    /** 응답 externalJobId 최대 길이. 외부 시스템이 비정상적으로 긴 값을 보내는 케이스 차단. */
    private static final int MAX_EXTERNAL_JOB_ID_LENGTH = 128;

    /** 응답 externalJobId 허용 문자 패턴 — 영숫자 + dash + underscore. */
    private static final Pattern EXTERNAL_JOB_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]+$");

    /** 응답 status 화이트리스트. 그 외 값은 차단 (CWE-20 입력 검증). */
    private static final Set<String> ALLOWED_STATUSES =
            Set.of("ACCEPTED", "QUEUED", "REJECTED", "SKIPPED");

    /** 로그 sanitize 패턴 — CR/LF/TAB 제거 (CWE-117 Log Injection 차단). */
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
     * 시계열 메타 분석을 외부 VLM 서비스에 비동기 위탁한다.
     *
     * <p>enabled=false 면 외부 호출 없이 즉시 SKIPPED 응답을 반환한다 (NO-OP).
     * enabled=true 면 Retry + CircuitBreaker + 응답 무결성 검증이 적용된 외부 호출을 수행한다.
     *
     * @param request 위탁 요청. {@code idempotencyKey} 가 null/blank 이면 UUID 로 자동 발급.
     * @return 외부 시스템의 수락 응답 (externalJobId + idempotencyKey + status)
     */
    public Mono<VlmTimeseriesResponse> submitTimeseries(VlmTimeseriesRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        String idemKey = resolveIdempotencyKey(request.idempotencyKey());
        VlmTimeseriesRequest enriched = new VlmTimeseriesRequest(
                request.rawSn(), request.videoUri(), idemKey, request.callbackUrl(),
                request.eventName(), request.marks());

        if (!enabled) {
            log.info("[Vlm] timeseries skipped (disabled) rawSn={} idempotencyKey={}",
                    enriched.rawSn(), idemKey);
            return Mono.just(VlmTimeseriesResponse.skipped(idemKey));
        }

        log.info("[Vlm] timeseries submit rawSn={} idempotencyKey={}",
                enriched.rawSn(), idemKey);
        return webClient.post()
                .uri(SUBMIT_PATH)
                .header(IDEMPOTENCY_HEADER, idemKey)
                .bodyValue(enriched)
                .retrieve()
                .bodyToMono(VlmTimeseriesResponse.class)
                .timeout(timeout)
                .map(this::validateResponse)
                .transformDeferred(RetryOperator.of(retry))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }

    /**
     * 호출자가 idempotencyKey 를 제공하지 않으면 UUID 로 발급.
     *
     * <p>DEV_FIX-1: 멱등 키 발급 책임을 본 클라이언트로 단일화. 호출자(Step)는 null 만
     * 전달하면 자동 발급된다. Resilience4j Retry 는 동일 Mono 를 재구독하므로 재시도 시
     * 같은 키가 헤더·페이로드 양쪽에 그대로 유지된다.
     */
    private String resolveIdempotencyKey(String provided) {
        if (provided == null || provided.isBlank()) {
            return UUID.randomUUID().toString();
        }
        return provided;
    }

    /**
     * 외부 응답 무결성 검증 — DEV_FIX-1 신설.
     *
     * <p>외부 시스템이 신뢰 영역 밖이므로 응답 값은 모두 화이트리스트/길이/패턴으로 검증한다.
     * 검증 실패 시 {@link CustomException}{@code (EXTERNAL_API_ERROR)} 로 변환되어
     * Resilience4j Retry 가 재시도하거나 최종적으로 BatchOrchestrator FAILED 경로로 흐른다.
     *
     * @return 검증을 통과한 응답 그대로 반환
     */
    private VlmTimeseriesResponse validateResponse(VlmTimeseriesResponse resp) {
        if (resp == null) {
            throw new CustomException(ErrorCode.EXTERNAL_API_ERROR, "VLM 응답이 null 입니다.");
        }
        String jobId = resp.externalJobId();
        if (jobId == null || jobId.isBlank()) {
            throw new CustomException(ErrorCode.EXTERNAL_API_ERROR,
                    "VLM 응답 externalJobId 가 비어있습니다.");
        }
        if (jobId.length() > MAX_EXTERNAL_JOB_ID_LENGTH) {
            throw new CustomException(ErrorCode.EXTERNAL_API_ERROR,
                    "VLM 응답 externalJobId 가 허용 길이("
                            + MAX_EXTERNAL_JOB_ID_LENGTH + ") 를 초과했습니다.");
        }
        if (!EXTERNAL_JOB_ID_PATTERN.matcher(jobId).matches()) {
            throw new CustomException(ErrorCode.EXTERNAL_API_ERROR,
                    "VLM 응답 externalJobId 형식이 올바르지 않습니다.");
        }
        String status = resp.status();
        if (status == null || !ALLOWED_STATUSES.contains(status)) {
            throw new CustomException(ErrorCode.EXTERNAL_API_ERROR,
                    "VLM 응답 status 가 허용 목록 밖입니다: " + safeForLog(status));
        }
        return resp;
    }

    /**
     * 로그 출력용 외부 입력 sanitize — CWE-117 Log Injection 차단.
     *
     * <p>외부 응답 값({@code externalJobId}, {@code status} 등) 을 로그에 출력하기 전 호출.
     * CR/LF/TAB 을 {@code _} 로 치환하여 다중 로그라인 위조를 방지한다.
     * 본 도구가 발급한 값(UUID idempotencyKey, 내부 rawSn) 은 sanitize 불필요.
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
