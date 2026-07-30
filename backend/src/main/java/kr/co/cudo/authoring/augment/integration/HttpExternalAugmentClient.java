package kr.co.cudo.authoring.augment.integration;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.github.resilience4j.reactor.retry.RetryOperator;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import kr.co.cudo.authoring.augment.integration.dto.GenAiInputFile;
import kr.co.cudo.authoring.augment.integration.dto.GenAiJobAcceptedResponse;
import kr.co.cudo.authoring.augment.integration.dto.GenAiJobSubmitRequest;
import kr.co.cudo.authoring.common.client.NonRetryableExternalException;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 외부 증강(생성형 AI) 위탁 클라이언트 — 「생성형 AI API 연동명세서 v1.1」 §4.1 정합 (Phase 7-A1).
 *
 * <p>{@code POST /api/genai/jobs} 로 증강을 <b>비동기 위탁</b>하고 202 응답에서 외부가 발급한
 * {@code job_id} 를 받아온다. 결과는 웹훅으로 수신한다(A2 스코프).
 *
 * <h3>설계 원칙</h3>
 * <ul>
 *   <li><b>멱등</b>: 우리가 발급한 {@code request_id} 를 {@code Idempotency-Key} 헤더로도 보낸다.
 *       외부는 동일 키 재요청 시 기존 job 을 그대로 반환하므로 재시도로 중복 job 이 생기지 않는다.</li>
 *   <li><b>인증 없음</b>: 명세서·벤더 목 모두 인증 미구현이 확정 계약이라 인증 헤더를 붙이지 않는다.</li>
 *   <li><b>4xx 는 재시도 금지</b>: 요청이 이미 외부에 도달했을 수 있는 결정적 오류를 재시도하면
 *       중복 위탁·불필요한 백오프가 된다. {@link NonRetryableExternalException} 으로 분류해
 *       Retry/CircuitBreaker 에서 제외한다(연결 실패·타임아웃·5xx 만 재시도).</li>
 *   <li><b>응답 검증</b>: request_id echo 일치 + {@code status=RECEIVED} + job_id non-blank.
 *       하나라도 어긋나면 {@link ErrorCode#EXTERNAL_API_ERROR}.</li>
 *   <li><b>로그</b>: 파일 절대경로를 남기지 않는다(개수·식별자 수준). CR/LF sanitize(CWE-117).</li>
 * </ul>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "authoring.augment.external.mode", havingValue = "http",
        matchIfMissing = true)
public class HttpExternalAugmentClient implements ExternalAugmentClient {

    /** 명세서 §4.1 위탁 엔드포인트. */
    static final String JOBS_PATH = "/api/genai/jobs";

    /** 명세서 §4.1 멱등 헤더 — 값은 우리가 발급한 request_id 와 동일하다. */
    static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

    /** 로그 sanitize — CR/LF/TAB 제거(CWE-117). */
    private static final Pattern LOG_UNSAFE = Pattern.compile("[\\r\\n\\t]");

    private final WebClient webClient;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;
    private final Duration timeout;

    public HttpExternalAugmentClient(
            @Qualifier("augmentApiWebClient") WebClient webClient,
            CircuitBreakerRegistry circuitBreakerRegistry,
            RetryRegistry retryRegistry,
            @Value("${authoring.augment.external.timeout-seconds:10}") long timeoutSeconds) {
        this.webClient = webClient;
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker("augmentClient");
        this.retry = retryRegistry.retry("augmentClient");
        this.timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
    }

    /**
     * 검수 결정 통보 — 외부 계약에 해당 엔드포인트가 없어 본 Phase 범위 밖이다.
     * 호출부(AugmentReviewService)를 깨지 않도록 no-op 으로 두고 로그만 남긴다.
     */
    @Override
    public boolean syncDecision(Long dataAugSn, String decision, String reasonOrNull) {
        log.info("[Augment] decision sync skipped (외부 계약 미정의) dataAugSn={} decision={}",
                dataAugSn, safe(decision));
        return true;
    }

    /**
     * 위탁 제출 — <b>논블로킹</b>. 구독 시점에 호출을 개시하고 202 ACK 도 기다리지 않는다 (Phase C-3).
     *
     * <p>구 구현은 {@code .block()} 으로 ACK 왕복(타임아웃 10s × 재시도) 동안 호출 스레드를 붙잡았다.
     * 지금은 {@link Mono} 를 그대로 반환하고 호출부({@code AugmentJobSubmitService})가 전용 스케줄러로
     * 완료 신호를 옮겨 처리한다.
     *
     * <p><b>연산자 순서는 의미가 있다</b>: 응답 검증({@link #validate})은 재시도/서킷 연산자 <b>뒤</b>에
     * 둔다 — 구 구현이 {@code block()} 이후에 검증했던 것과 동일한 의미다. 앞에 두면 계약 위반 응답
     * (request_id echo 불일치 등)이 재시도 대상이 되어 <b>중복 위탁</b>이 된다.
     *
     * <p>빈 응답(onComplete only)은 어느 핸들러도 타지 않으므로 {@code switchIfEmpty} 로 실패로 승격한다
     * (구 구현의 {@code response == null} 가드와 동일 판정).
     */
    @Override
    public Mono<AugmentSubmitResult> requestAugment(AugmentSubmitCommand command) {
        GenAiJobSubmitRequest body = toRequestBody(command);

        return Mono.defer(() -> {
            log.info("[Augment] genai submit originAugSn={} augType={} jobSeq={}/{} inputCount={}",
                    command.originAugSn(), safe(command.augType()),
                    command.jobSeq(), command.jobCount(), command.inputFiles().size());
            return webClient.post()
                    .uri(JOBS_PATH)
                    .header(IDEMPOTENCY_HEADER, command.requestId())
                    .bodyValue(body)
                    .retrieve()
                    // 4xx = 결정적 오류(형식/경로/이벤트유형). 재시도해도 결과가 같고 중복 위탁 위험만 는다.
                    .onStatus(HttpStatusCode::is4xxClientError, this::toNonRetryable4xx)
                    .bodyToMono(GenAiJobAcceptedResponse.class)
                    .timeout(timeout)
                    .transformDeferred(RetryOperator.of(retry))
                    .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
        })
                // 예외는 지연 생성한다(정상 경로에서 불필요한 스택트레이스 채움 방지).
                .switchIfEmpty(Mono.error(() -> new CustomException(
                        ErrorCode.EXTERNAL_API_ERROR, "생성형AI 위탁 응답이 비어있습니다.")))
                .map(response -> AugmentSubmitResult.accepted(validate(response, command.requestId())));
    }

    /** 커맨드 → 명세서 §4.1 요청 바디. 채널/작업유형/생성모드는 저작도구 증강 고정값이다. */
    private GenAiJobSubmitRequest toRequestBody(AugmentSubmitCommand command) {
        List<GenAiInputFile> files = command.inputFiles().stream()
                .map(f -> new GenAiInputFile(f.sequence(), f.filePath()))
                .toList();
        return new GenAiJobSubmitRequest(
                command.requestId(),
                GenAiJobSubmitRequest.CHANNEL_AUTHORING,
                command.requestUserId(),
                command.evntType(),
                GenAiJobSubmitRequest.OPERATION_AUGMENT,
                GenAiJobSubmitRequest.MODE_I2I,
                files,
                AugmentPrompts.of(command.augType()),
                command.callbackUrl());
    }

    /**
     * 4xx → 비재시도 예외. 본문을 소비/해제해 리소스 누수를 막고 상태 코드만 남긴다.
     * CWE-209: 외부 응답 본문 원문은 예외/로그에 노출하지 않는다.
     */
    private Mono<Throwable> toNonRetryable4xx(ClientResponse response) {
        int status = response.statusCode().value();
        log.warn("[Augment] genai submit non-retryable 4xx status={}", status);
        return response.releaseBody()
                .then(Mono.error(new NonRetryableExternalException(
                        "생성형AI 위탁 4xx 응답(status=" + status + ")")));
    }

    /**
     * 202 응답 무결성 검증 — 외부는 신뢰 영역 밖이다.
     *
     * @return 검증을 통과한 외부 발급 job_id
     */
    private String validate(GenAiJobAcceptedResponse response, String expectedRequestId) {
        if (response == null) {
            throw new CustomException(ErrorCode.EXTERNAL_API_ERROR, "생성형AI 위탁 응답이 null 입니다.");
        }
        if (response.requestId() == null || !response.requestId().equals(expectedRequestId)) {
            throw new CustomException(ErrorCode.EXTERNAL_API_ERROR,
                    "생성형AI 위탁 응답 request_id echo 불일치: " + safe(response.requestId()));
        }
        if (!AugmentSubmitResult.STATUS_RECEIVED.equals(response.status())) {
            throw new CustomException(ErrorCode.EXTERNAL_API_ERROR,
                    "생성형AI 위탁 응답 status 가 RECEIVED 가 아닙니다: " + safe(response.status()));
        }
        if (response.jobId() == null || response.jobId().isBlank()) {
            throw new CustomException(ErrorCode.EXTERNAL_API_ERROR,
                    "생성형AI 위탁 응답에 job_id 가 없습니다.");
        }
        return response.jobId();
    }

    private static String safe(String s) {
        if (s == null) return "null";
        return LOG_UNSAFE.matcher(s).replaceAll("_");
    }
}
