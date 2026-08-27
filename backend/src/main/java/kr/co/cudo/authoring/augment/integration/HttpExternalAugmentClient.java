package kr.co.cudo.authoring.augment.integration;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.github.resilience4j.reactor.retry.RetryOperator;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import kr.co.cudo.authoring.augment.integration.dto.GenAiCancelRequest;
import kr.co.cudo.authoring.augment.integration.dto.GenAiCancelResponse;
import kr.co.cudo.authoring.augment.integration.dto.GenAiContract;
import kr.co.cudo.authoring.augment.integration.dto.GenAiInputFile;
import kr.co.cudo.authoring.augment.integration.dto.GenAiJobAcceptedResponse;
import kr.co.cudo.authoring.augment.integration.dto.GenAiJobResultsResponse;
import kr.co.cudo.authoring.augment.integration.dto.GenAiJobStatusResponse;
import kr.co.cudo.authoring.augment.integration.dto.GenAiJobSubmitRequest;
import kr.co.cudo.authoring.common.client.NonRetryableExternalException;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.codec.CodecException;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.UnsupportedMediaTypeException;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 외부 증강(생성형 AI) 위탁 클라이언트 — 「생성형 AI API 연동명세서」 정합 (Phase 7-A1).
 *
 * <p>위탁 요청(§4.1) 본문은 <b>v1.3</b> 형태다 — 생성 조건은 최상위 {@code mtdt}, 자유 지시문은
 * 별개 {@code prompt} 문자열이다({@code @design INT-008}). 조회·취소(§4.4~§4.6)는 이번 정합에서
 * 바뀌지 않았고 각 DTO 주석이 자기 절의 판을 적는다.
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

    /** 명세서 §4.4 상태 조회. 경로 변수는 URI 템플릿으로 넘겨 인코딩을 위임한다. */
    static final String JOB_STATUS_PATH = JOBS_PATH + "/{jobId}";

    /** 명세서 §4.5 결과 조회. */
    static final String JOB_RESULTS_PATH = JOBS_PATH + "/{jobId}/results";

    /** 명세서 §4.6 취소. */
    static final String JOB_CANCEL_PATH = JOBS_PATH + "/{jobId}/cancel";

    /** 위탁 경로의 Resilience4j 인스턴스명. */
    static final String SUBMIT_RESILIENCE_NAME = "augmentClient";

    /**
     * <b>자동 폴링</b>(상태·결과 조회)의 Resilience4j 인스턴스명 — 위탁과 <b>분리</b>한다.
     *
     * <p>같은 인스턴스를 공유하면 폴링(호출량이 화면 수 × 주기로 곱해진다)이 5xx·타임아웃을 반복할 때
     * 서킷이 열려 <b>신규 증강 위탁까지 차단</b>된다. 조회 장애가 위탁을 막는 것은 장애 전파다.
     */
    static final String QUERY_RESILIENCE_NAME = "augmentQuery";

    /**
     * <b>취소</b> 전용 인스턴스 — 폴링과도 분리한다 (DEV_FIX MED-1b).
     *
     * <p>취소는 <b>사용자가 직접 누르는 능동 행위</b>이고 폴링은 배경 자동 호출이다. 둘이 인스턴스를
     * 공유하면 "상태 조회 응답만 깨뜨리는 벤더" 가 서킷을 열어 <b>사용자 취소를 30초 단위로 반복
     * 차단</b>한다 — 폴링 장애가 취소를 인질로 잡는 구조다. 취소를 아예 서킷 없이 두는 안도 있으나,
     * 그러면 벤더가 무응답일 때 취소 요청이 매번 타임아웃 예산을 소진하므로(사용자는 다시 누른다)
     * <b>자기 서킷을 갖되 폴링 실패에 오염되지 않는</b> 쪽을 택했다. 호출량이 낮아 표본은 작게 잡는다.
     */
    static final String CANCEL_RESILIENCE_NAME = "augmentCancel";

    /** 명세서 §4.1 멱등 헤더 — 값은 우리가 발급한 request_id 와 동일하다. */
    static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

    /**
     * 로그 sanitize — 개행 계열 제거(CWE-117).
     *
     * <p>{@code U+2028}(LINE SEPARATOR)/{@code U+2029}(PARAGRAPH SEPARATOR)까지 포함한다. 이 둘은
     * JSON 로그 소비자·뷰어에서 줄바꿈으로 해석되어 CR/LF 와 동일한 로그 위조 벡터가 된다
     * ({@code VisibleTextNormalizer} Javadoc 이 같은 판단을 이미 명시하고 있다 — 정책 비대칭 제거).
     */
    private static final Pattern LOG_UNSAFE = Pattern.compile("[\\r\\n\\t\\u2028\\u2029]");

    /** 외부에서 온 문자열을 로그에 실을 때의 길이 상한. */
    private static final int LOG_VALUE_MAX = 50;

    private final WebClient webClient;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;
    private final CircuitBreaker queryCircuitBreaker;
    private final Retry queryRetry;
    private final CircuitBreaker cancelCircuitBreaker;
    private final Retry cancelRetry;
    private final Duration timeout;

    /**
     * <b>WebClient 는 주입받은 것만 쓴다</b> — 여기서 {@code WebClient.builder()} 를 새로 부르면
     * {@code AugmentApiWebClientConfig} 에 걸린 URL 정책(prd/stg HTTPS·공인망 강제, 사설망/메타데이터
     * 차단)을 통째로 우회한다. 조회 3종도 같은 빈을 재사용해 커넥션 풀·정책을 공유한다.
     */
    public HttpExternalAugmentClient(
            @Qualifier("augmentApiWebClient") WebClient webClient,
            CircuitBreakerRegistry circuitBreakerRegistry,
            RetryRegistry retryRegistry,
            @Value("${authoring.augment.external.timeout-seconds:10}") long timeoutSeconds) {
        this.webClient = webClient;
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(SUBMIT_RESILIENCE_NAME);
        this.retry = retryRegistry.retry(SUBMIT_RESILIENCE_NAME);
        this.queryCircuitBreaker = circuitBreakerRegistry.circuitBreaker(QUERY_RESILIENCE_NAME);
        this.queryRetry = retryRegistry.retry(QUERY_RESILIENCE_NAME);
        this.cancelCircuitBreaker = circuitBreakerRegistry.circuitBreaker(CANCEL_RESILIENCE_NAME);
        this.cancelRetry = retryRegistry.retry(CANCEL_RESILIENCE_NAME);
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
     *
     * <p><b>바디 조립도 {@code defer} 안</b>에서 한다 — {@link #toRequestBody} 는 계약 위반(빈 prompt)에
     * 예외를 던지는데, {@code defer} 밖에서 부르면 그 예외가 <b>Mono 반환 전에 동기로</b> 튀어 나가
     * 호출부의 reactive 오류 처리({@code onErrorResume} → job 행에 사유 기록)를 건너뛴다.
     */
    @Override
    public Mono<AugmentSubmitResult> requestAugment(AugmentSubmitCommand command) {
        return Mono.defer(() -> {
            GenAiJobSubmitRequest body = toRequestBody(command);
            log.info("[Augment] genai submit originAugSn={} augType={} jobSeq={}/{} inputCount={}",
                    command.originAugSn(), safe(command.augType()),
                    command.jobSeq(), command.jobCount(), command.inputFiles().size());
            return webClient.post()
                    .uri(JOBS_PATH)
                    .header(IDEMPOTENCY_HEADER, command.requestId())
                    .bodyValue(body)
                    .retrieve()
                    // 4xx = 결정적 오류(형식/경로/이벤트유형). 재시도해도 결과가 같고 중복 위탁 위험만 는다.
                    .onStatus(HttpStatusCode::is4xxClientError,
                            response -> toNonRetryable4xx(response, "위탁"))
                    .bodyToMono(GenAiJobAcceptedResponse.class)
                    .onErrorMap(HttpExternalAugmentClient::isDecodingFailure,
                            e -> toNonRetryableDecoding(e, "위탁"))
                    .timeout(timeout)
                    .transformDeferred(RetryOperator.of(retry))
                    .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
        })
                // 예외는 지연 생성한다(정상 경로에서 불필요한 스택트레이스 채움 방지).
                .switchIfEmpty(Mono.error(() -> new CustomException(
                        ErrorCode.EXTERNAL_API_ERROR, "생성형AI 위탁 응답이 비어있습니다.")))
                .map(response -> AugmentSubmitResult.accepted(validate(response, command.requestId())));
    }

    /**
     * 상태 조회 — §4.4. 위탁과 <b>다른 서킷/재시도 인스턴스</b>({@link #QUERY_RESILIENCE_NAME})를 탄다.
     *
     * <p>연산자 순서는 위탁과 같은 이유로 의미가 있다: 계약 검증({@link #validateStatus})은 재시도/서킷
     * <b>뒤</b>에 둔다. 앞에 두면 계약 위반 응답이 재시도 대상이 되어 무의미한 왕복만 늘어난다.
     */
    @Override
    public Mono<AugmentQueryResult<GenAiJobStatusResponse>> fetchJobStatus(String externalJobId) {
        return Mono.defer(() -> {
            String jobId = requireValidJobId(externalJobId, "상태조회");
            return webClient.get()
                    .uri(builder -> builder.path(JOB_STATUS_PATH).build(jobId))
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError,
                            response -> toNonRetryable4xx(response, "상태조회"))
                    .bodyToMono(GenAiJobStatusResponse.class)
                    .onErrorMap(HttpExternalAugmentClient::isDecodingFailure,
                            e -> toNonRetryableDecoding(e, "상태조회"))
                    .timeout(timeout)
                    .transformDeferred(RetryOperator.of(queryRetry))
                    .transformDeferred(CircuitBreakerOperator.of(queryCircuitBreaker))
                    .switchIfEmpty(Mono.error(() -> emptyBody("상태조회")))
                    .map(response -> AugmentQueryResult.of(validateStatus(response, jobId)));
        });
    }

    /**
     * 결과 조회 — §4.5. {@code SUCCEEDED} 에서만 200 이며 그 외는 409 {@code STATE_CONFLICT}(비재시도)다.
     *
     * <p>반환 경로({@code output_file_path})는 <b>파싱만</b> 한다. 파일시스템에 넘기기 전 허용 루트
     * 검증은 소비 계층의 책임이다(인터페이스 Javadoc 경고 참조 — CWE-22).
     */
    @Override
    public Mono<AugmentQueryResult<GenAiJobResultsResponse>> fetchJobResults(String externalJobId) {
        return Mono.defer(() -> {
            String jobId = requireValidJobId(externalJobId, "결과조회");
            return webClient.get()
                    .uri(builder -> builder.path(JOB_RESULTS_PATH).build(jobId))
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError,
                            response -> toNonRetryable4xx(response, "결과조회"))
                    .bodyToMono(GenAiJobResultsResponse.class)
                    .onErrorMap(HttpExternalAugmentClient::isDecodingFailure,
                            e -> toNonRetryableDecoding(e, "결과조회"))
                    .timeout(timeout)
                    .transformDeferred(RetryOperator.of(queryRetry))
                    .transformDeferred(CircuitBreakerOperator.of(queryCircuitBreaker))
                    .switchIfEmpty(Mono.error(() -> emptyBody("결과조회")))
                    .map(response -> AugmentQueryResult.of(validateResults(response, jobId)));
        });
    }

    /**
     * 취소 — §4.6. 로컬 DB 가 이미 종결이면 외부 호출을 <b>개시하지 않는다</b>(확정적 409 왕복 제거).
     *
     * <p>{@code requested_by} 는 커맨드가 인증 주체에서 파생시킨 값만 실린다(호출부 자유 문자열 불가).
     *
     * <p><b>서킷/재시도는 {@link #CANCEL_RESILIENCE_NAME} 전용 인스턴스</b>다 — 자동 폴링 장애가
     * 사용자의 능동 취소를 인질로 잡지 않게 한다(상수 Javadoc 참조).
     */
    @Override
    public Mono<AugmentQueryResult<GenAiCancelResponse>> cancelJob(AugmentCancelCommand command) {
        return Mono.defer(() -> {
            if (command.localTerminal()) {
                // ★ 이 분기는 형식 검증(requireValidJobId) <앞>이라 job_id 가 아직 임의 길이일 수 있다 —
                //   길이 상한을 함께 거는 logValue 를 쓴다(CWE-770).
                log.info("[Augment] genai cancel skipped (이미 종결된 job) job_id={}",
                        logValue(command.externalJobId()));
                return Mono.just(AugmentQueryResult.<GenAiCancelResponse>skipped(
                        AugmentQueryResult.SkipReason.LOCAL_TERMINAL));
            }
            String jobId = requireValidJobId(command.externalJobId(), "취소");
            // 사유 원문은 로그에 남기지 않는다(자유 입력 — 길이만 남긴다).
            log.info("[Augment] genai cancel job_id={} reasonLength={}",
                    safe(jobId), command.reason() == null ? 0 : command.reason().length());
            return webClient.post()
                    .uri(builder -> builder.path(JOB_CANCEL_PATH).build(jobId))
                    .bodyValue(new GenAiCancelRequest(command.reason(), command.requestedBy()))
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError,
                            response -> toNonRetryable4xx(response, "취소"))
                    .bodyToMono(GenAiCancelResponse.class)
                    .onErrorMap(HttpExternalAugmentClient::isDecodingFailure,
                            e -> toNonRetryableDecoding(e, "취소"))
                    .timeout(timeout)
                    // 취소는 폴링과 <다른> 인스턴스를 탄다 — 폴링 장애가 사용자 취소를 막지 않게.
                    .transformDeferred(RetryOperator.of(cancelRetry))
                    .transformDeferred(CircuitBreakerOperator.of(cancelCircuitBreaker))
                    .switchIfEmpty(Mono.error(() -> emptyBody("취소")))
                    .map(response -> AugmentQueryResult.of(validateCancel(response, jobId)));
        });
    }

    /**
     * 외부 job_id fail-fast 검증 — 형식 위반이면 <b>호출을 개시하지 않는다</b>.
     *
     * <p>202 를 놓쳐 {@code OTSD_JOB_ID} 가 null 인 job 이 실제로 존재하므로 null/공백을 먼저 끊는다.
     * 형식 검증은 경로 세그먼트 조작(CWE-22) 방어이기도 하다 — URI 템플릿 인코딩과 이중으로 건다.
     */
    private String requireValidJobId(String externalJobId, String operation) {
        if (!GenAiContract.isValidJobId(externalJobId)) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "생성형AI " + operation + " 대상 job_id 가 올바르지 않습니다.");
        }
        return externalJobId;
    }

    /**
     * §4.4 응답 계약 검증 — fail-closed.
     *
     * <p>미지 {@code status} 를 통과시키면 소비 계층이 성공/실패를 오판한다(영구 대기 또는 성공 누락).
     * {@code error_code} 는 판정 축이 아니므로 <b>미등록 값</b>이어도 실패시키지 않고 WARN 으로 드러낸다.
     *
     * <h3>길이는 입구에서 막는다 — 웹훅 경로와 대칭 (DEV_FIX MED-3)</h3>
     * <p>웹훅 DTO({@code GenAiCallbackRequest})는 {@code error_code}({@code @Size(max=50)}) ·
     * {@code error_message}({@code @Size(max=1000)}) 를 <b>입구에서</b> 거부하는데 조회 경로에는 그 검증이
     * 없었다. 그래서 벤더가 51자짜리 {@code error_code} 를 주면 {@code LsDataAugJob.markFailed} 가 절단
     * 없이 대입 → {@code ERR_CD VARCHAR(50)} 에 {@code 22001}(value too long) → 트랜잭션 롤백 →
     * 회수 경로가 예외를 삼켜 200 → <b>그 job 이 회수 불가로 고착</b>되고 매 폴링마다 실패 트랜잭션 +
     * 외부 호출 2회를 만료(6시간)까지 반복했다. 같은 값의 계약 강도가 경로마다 다르면 안 된다.
     */
    private GenAiJobStatusResponse validateStatus(GenAiJobStatusResponse response, String expectedJobId) {
        validateEcho(response.requestId(), response.jobId(), expectedJobId, "상태조회");
        if (!GenAiContract.isKnownStatus(response.status())) {
            throw contractViolation("상태조회", "status", response.status());
        }
        Integer progress = response.progress();
        if (progress != null && (progress < 0 || progress > 100)) {
            throw new CustomException(ErrorCode.EXTERNAL_API_ERROR,
                    "생성형AI 상태조회 응답의 progress 가 계약 범위를 벗어났습니다.");
        }
        requireWithinContractLength(response.errorCode(),
                GenAiJobStatusResponse.ERROR_CODE_MAX, "error_code");
        requireWithinContractLength(response.errorMessage(),
                GenAiJobStatusResponse.ERROR_MESSAGE_MAX, "error_message");
        if (response.errorCode() != null && !response.errorCode().isBlank()
                && !response.hasKnownErrorCode()) {
            log.warn("[Augment] genai status unknown error_code job_id={} error_code={}",
                    safe(expectedJobId), logValue(response.errorCode()));
        }
        return response;
    }

    /**
     * 계약 상한 초과 → 계약 위반으로 끊는다(절단하지 않는다).
     *
     * <p>절단해서 통과시키면 "벤더가 계약을 어겼다" 는 사실이 사라져 운영이 인지하지 못한다. 끊으면
     * 그 job 은 비종결로 남아 다음 폴링·만료 스윕이 처리하므로 <b>고착되지 않는다</b>(웹훅 400 거부와
     * 동일한 태도).
     */
    private void requireWithinContractLength(String value, int max, String field) {
        if (value != null && value.length() > max) {
            throw contractViolation("상태조회", field, value);
        }
    }

    /** §4.5 응답 계약 검증 — {@code SUCCEEDED} + 비어 있지 않은 {@code results} 가 계약이다. */
    private GenAiJobResultsResponse validateResults(GenAiJobResultsResponse response, String expectedJobId) {
        validateEcho(response.requestId(), response.jobId(), expectedJobId, "결과조회");
        if (!GenAiContract.STATUS_SUCCEEDED.equals(response.status())) {
            throw contractViolation("결과조회", "status", response.status());
        }
        List<GenAiJobResultsResponse.ResultItem> results = response.results();
        if (results.isEmpty()) {
            // 성공으로 접수하면 빈 증강본이 확정된다 — 웹훅 경로와 동일 규칙(fail-closed).
            throw new CustomException(ErrorCode.EXTERNAL_API_ERROR,
                    "생성형AI 결과조회 응답에 results 가 없습니다.");
        }
        if (results.size() > GenAiJobResultsResponse.MAX_RESULTS) {
            throw new CustomException(ErrorCode.EXTERNAL_API_ERROR,
                    "생성형AI 결과조회 응답의 results 가 계약 상한을 초과했습니다: " + results.size());
        }
        for (GenAiJobResultsResponse.ResultItem item : results) {
            validateResultItem(item);
        }
        return response;
    }

    /** 산출물 1건 검증 — 필수 필드 누락·계약 밖 media_type·과대 경로는 접수하지 않는다. */
    private void validateResultItem(GenAiJobResultsResponse.ResultItem item) {
        if (isBlank(item.generatedDataId())) {
            throw new CustomException(ErrorCode.EXTERNAL_API_ERROR,
                    "생성형AI 결과 항목에 generated_data_id 가 없습니다.");
        }
        if (!GenAiContract.isKnownMediaType(item.mediaType())) {
            throw contractViolation("결과조회", "media_type", item.mediaType());
        }
        if (isBlank(item.outputFilePath())
                || item.outputFilePath().length() > GenAiJobResultsResponse.ResultItem.OUTPUT_PATH_MAX) {
            // 경로 원문은 로그·예외에 남기지 않는다(CWE-209 / 저장 구조 노출 방지).
            throw new CustomException(ErrorCode.EXTERNAL_API_ERROR,
                    "생성형AI 결과 항목의 output_file_path 가 계약에 맞지 않습니다.");
        }
    }

    /** §4.6 응답 계약 검증 — 취소는 웹훅이 없어 이 동기 응답이 유일한 통보다(오접수 금지). */
    private GenAiCancelResponse validateCancel(GenAiCancelResponse response, String expectedJobId) {
        validateEcho(response.requestId(), response.jobId(), expectedJobId, "취소");
        if (!GenAiContract.STATUS_CANCELED.equals(response.status())) {
            throw contractViolation("취소", "status", response.status());
        }
        if (isBlank(response.canceledAt())) {
            throw new CustomException(ErrorCode.EXTERNAL_API_ERROR,
                    "생성형AI 취소 응답에 canceled_at 이 없습니다.");
        }
        return response;
    }

    /**
     * 공통 응답 정합 검증 — 강도가 두 필드에서 <b>다르다</b>(과장 없이 적는다).
     *
     * <ul>
     *   <li>{@code job_id} — <b>동등 비교</b>. 우리가 지목한 job 인지 확인한다. 프록시·벤더 버그로 다른
     *       job 의 응답을 받으면 남의 상태를 우리 job 의 것으로 믿게 된다.</li>
     *   <li>{@code request_id} — <b>존재(non-blank) 확인까지만</b>. 비교 대상(원 위탁의 request_id)은
     *       {@code LS_DATA_AUG_JOB} 에 있는데 이 클라이언트는 DB 를 읽지 않고(순수 HTTP 어댑터)
     *       조회 3종 시그니처도 그 값을 받지 않는다 — <b>설계 갭이며 여기서 메워지지 않는다</b>.
     *       동등 비교를 원하면 호출부(Phase 3)가 기대 {@code request_id} 를 커맨드에 실어 넘기고
     *       이 메서드가 비교하도록 확장해야 한다. 지금 강도는 "존재 확인" 이다.</li>
     * </ul>
     */
    private void validateEcho(String requestId, String jobId, String expectedJobId, String operation) {
        if (isBlank(requestId)) {
            throw new CustomException(ErrorCode.EXTERNAL_API_ERROR,
                    "생성형AI " + operation + " 응답에 request_id 가 없습니다.");
        }
        if (!expectedJobId.equals(jobId)) {
            throw contractViolation(operation, "job_id echo", jobId);
        }
    }

    /**
     * 계약 위반 → 사용자 응답에는 <b>외부 원문을 싣지 않는다</b> (CWE-209/770).
     *
     * <p>{@code GlobalExceptionHandler} 가 {@code e.getMessage()} 를 WARN 로그 <b>+ 응답 본문</b>에
     * 그대로 싣는다. 외부 문자열은 길이 상한이 없으므로(벤더가 200KB 짜리 {@code status} 를 보낼 수
     * 있다) 원문을 메시지에 넣으면 폴링 1회당 그 크기의 로그·응답이 생긴다. 축약 원문은
     * <b>로그에만</b> 남기고, 사용자에게는 무엇이 어긋났는지(필드명 — 우리 리터럴)만 알린다.
     */
    private CustomException contractViolation(String operation, String field, String rawValue) {
        log.warn("[Augment] genai {} contract violation field={} value={}",
                operation, field, logValue(rawValue));
        return new CustomException(ErrorCode.EXTERNAL_API_ERROR,
                "생성형AI " + operation + " 응답 형식이 계약과 다릅니다(" + field + ").");
    }

    /**
     * 디코딩 계열 실패인가 — <b>재시도·서킷 집계 대상이 아니다</b> (DEV_FIX MED-1a).
     *
     * <p>계약 검증({@code validate*})은 재시도/서킷 연산자 <b>뒤</b>라 애초에 재시도되지 않지만,
     * <b>디코딩은 {@code bodyToMono(...)} 안 = 연산자 앞</b>에서 일어난다. 그래서 {@code results:[null]}
     * (compact 생성자 NPE) · 본문 크기 초과 · 숫자 범위 초과 · 비-JSON 본문 같은 <b>결정적</b> 실패가
     * 전부 재시도되고 서킷 failure 로 집계됐다. 폴링은 호출량이 곱해지므로 이 오염만으로도
     * {@code minimum-number-of-calls}/{@code failure-rate} 를 채워 조회 서킷을 열 수 있다.
     */
    private static boolean isDecodingFailure(Throwable e) {
        // 원인 체인을 훑는다 — 계약 밖 Content-Type 은 WebClient 가 UnsupportedMediaTypeException 을
        // WebClientResponseException(200) 으로 <감싸서> 던진다(실측). 최상위 타입만 보면 놓친다.
        for (Throwable c = e; c != null && c != c.getCause(); c = c.getCause()) {
            if (c instanceof CodecException
                    || c instanceof DataBufferLimitException
                    || c instanceof UnsupportedMediaTypeException) {
                return true;
            }
        }
        return false;
    }

    /**
     * 디코딩 실패 → 비재시도 분류.
     *
     * <p><b>{@code cause} 를 싣지 않는다</b> — Jackson 의 디코딩 예외 메시지는 파싱하다 만
     * <b>외부 본문 조각</b>을 포함하고, {@link NonRetryableExternalException} 은 "외부 응답 원문/
     * 스택트레이스를 보존하지 않는다"(CWE-209)가 계약이다. 진단에 필요한 예외 <b>종류</b>만 로그로 남긴다.
     */
    private NonRetryableExternalException toNonRetryableDecoding(Throwable cause, String operation) {
        log.warn("[Augment] genai {} 응답 디코딩 실패 type={}", operation, cause.getClass().getSimpleName());
        return new NonRetryableExternalException("생성형AI " + operation + " 응답을 해석할 수 없습니다.");
    }

    /** 빈 응답(onComplete only)은 어느 핸들러도 타지 않으므로 실패로 승격한다. */
    private CustomException emptyBody(String operation) {
        return new CustomException(ErrorCode.EXTERNAL_API_ERROR,
                "생성형AI " + operation + " 응답이 비어있습니다.");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * 커맨드 → 명세서 v1.3 §4.1 요청 바디. 채널/작업유형/생성모드는 저작도구 증강 고정값이다.
     *
     * <p><b>여기는 매핑만 한다</b>. {@code mtdt}·{@code prompt}·{@code evnt_type} 은 모두 <b>요청 시점에
     * 확정된 값</b>({@code AugmentRequestService} → {@code AugmentPrompts.mtdt})을 그대로 중계한다.
     * 클라이언트가 자체 조립하거나 DB 를 다시 읽어 재구성하면 적재 원문
     * ({@code LS_DATA_AUG.PROMPT_CN})과 실제로 나간 값이 갈라진다.
     *
     * <p>{@code mtdt} 는 계약상 <b>최소 1항목</b>이 필요하므로 비어 있으면 fail-closed 로 끊는다. 빈
     * 객체를 그대로 보내면 벤더가 임의 기본값으로 채워 "요청한 조건과 다른 결과" 가 조용히 돌아온다.
     *
     * <p>{@code prompt} 는 <b>선택 문자열</b>이라 없으면 {@code null} 로 두고 {@code @JsonInclude}
     * (NON_NULL)가 키 자체를 생략한다. {@code evnt_subtype} 도 같다(침수가 아니면 미전송).
     */
    private GenAiJobSubmitRequest toRequestBody(AugmentSubmitCommand command) {
        List<GenAiInputFile> files = command.inputFiles().stream()
                .map(f -> new GenAiInputFile(f.sequence(), f.filePath()))
                .toList();
        if (command.mtdt().isEmpty()) {
            throw new CustomException(ErrorCode.INTERNAL_ERROR,
                    "증강 위탁 mtdt 가 비어 있습니다.");
        }
        return new GenAiJobSubmitRequest(
                command.requestId(),
                GenAiJobSubmitRequest.CHANNEL_AUTHORING,
                command.requestUserId(),
                command.evntType(),
                command.evntSubtype(),
                GenAiJobSubmitRequest.OPERATION_AUGMENT,
                GenAiJobSubmitRequest.MODE_I2I,
                files,
                command.mtdt(),
                command.promptText(),
                command.callbackUrl());
    }

    /**
     * 4xx → 비재시도 예외. 본문을 소비/해제해 리소스 누수를 막고 상태 코드만 남긴다.
     * CWE-209: 외부 응답 본문 원문은 예외/로그에 노출하지 않는다.
     *
     * <p>조회/취소의 404({@code JOB_NOT_FOUND})·409({@code STATE_CONFLICT})도 같은 경로를 탄다.
     * 이들은 재시도해도 결과가 같은 <b>결정적</b> 응답이라, 재시도하면 예산만 소진하고
     * 서킷까지 오염시킨다. {@link NonRetryableExternalException} 은 Retry·CircuitBreaker 양쪽의
     * {@code ignore-exceptions} 에 등록돼 있어 재시도되지도, failure 로 집계되지도 않는다.
     */
    private Mono<Throwable> toNonRetryable4xx(ClientResponse response, String operation) {
        int status = response.statusCode().value();
        log.warn("[Augment] genai {} non-retryable 4xx status={}", operation, status);
        // 상태 코드를 <값>으로 실어 보낸다 — 소비 계층(취소)이 결정적 거부(404/409)와 타임아웃을
        // 구분해야 하는데, 메시지 문자열을 파싱하게 두면 문구 변경에 조용히 오분류된다(MED-7).
        return response.releaseBody()
                .then(Mono.error(new NonRetryableExternalException(
                        "생성형AI " + operation + " 4xx 응답(status=" + status + ")", status)));
    }

    /**
     * 202 응답 무결성 검증 — 외부는 신뢰 영역 밖이다.
     *
     * <p><b>job_id 는 non-blank 로 끝내지 않고 계약 형식까지 본다</b>(ingress 검증 — DEV_FIX LOW-7).
     * 여기서 통과한 값이 {@code LS_DATA_AUG_JOB.OTSD_JOB_ID} 에 적재되고, 이후 조회/취소가 그 값을
     * <b>URL 경로 세그먼트</b>로 다시 내보낸다. 입구에서 걸러야 오염된 식별자가 DB 에 눌러앉지 않는다
     * (출구의 {@link #requireValidJobId} 는 그때 가서야 막으므로 그 job 은 영원히 조회 불가가 된다).
     *
     * @return 검증을 통과한 외부 발급 job_id
     */
    private String validate(GenAiJobAcceptedResponse response, String expectedRequestId) {
        if (response == null) {
            throw new CustomException(ErrorCode.EXTERNAL_API_ERROR, "생성형AI 위탁 응답이 null 입니다.");
        }
        if (response.requestId() == null || !response.requestId().equals(expectedRequestId)) {
            throw contractViolation("위탁", "request_id echo", response.requestId());
        }
        if (!AugmentSubmitResult.STATUS_RECEIVED.equals(response.status())) {
            throw contractViolation("위탁", "status", response.status());
        }
        if (!GenAiContract.isValidJobId(response.jobId())) {
            throw contractViolation("위탁", "job_id", response.jobId());
        }
        return response.jobId();
    }

    private static String safe(String s) {
        if (s == null) return "null";
        return LOG_UNSAFE.matcher(s).replaceAll("_");
    }

    /**
     * 외부 문자열을 로그에 실을 때의 단일 통로 — <b>sanitize + 길이 상한</b>을 함께 건다.
     *
     * <p>{@link #safe} 만 쓰면 개행은 막아도 길이는 무제한이라, 벤더가 200KB 짜리 {@code status} 를
     * 보내면 폴링 1회당 그 크기의 WARN 이 쌓인다(CWE-770 — 속도 제한은 사용자 확정으로 도입하지 않으므로
     * 길이 상한이 유일한 방어선이다).
     */
    private static String logValue(String s) {
        return s == null ? "null" : safe(abbreviate(s));
    }

    /** 외부 문자열은 길이 제한이 없다 — 로그로 흘릴 때 상한을 건다(로그 폭주 방지). */
    private static String abbreviate(String s) {
        return s.length() <= LOG_VALUE_MAX ? s : s.substring(0, LOG_VALUE_MAX) + "…(생략)";
    }
}
