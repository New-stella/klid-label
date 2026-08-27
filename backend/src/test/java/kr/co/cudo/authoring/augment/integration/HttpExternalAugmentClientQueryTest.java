package kr.co.cudo.authoring.augment.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import kr.co.cudo.authoring.augment.integration.dto.GenAiCancelResponse;
import kr.co.cudo.authoring.augment.integration.dto.GenAiJobResultsResponse;
import kr.co.cudo.authoring.augment.integration.dto.GenAiJobStatusResponse;
import kr.co.cudo.authoring.common.client.NonRetryableExternalException;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link HttpExternalAugmentClient} 조회/취소 3종 — 「생성형 AI API 연동명세서 v1.1」 §4.4/§4.5/§4.6
 * (INT-020 / INT-030 / INT-031) 계약 정합 테스트.
 *
 * <p>목 서버(mock-server/app/routers/augment.py)가 계약 정본이므로 그 경로·상태 규칙·오류 코드를
 * 그대로 단언한다. 방어 시나리오(서킷 분리 · 4xx 비재시도 · fail-closed 파싱 · fail-fast job_id)도
 * 여기서 함께 고정한다.
 */
class HttpExternalAugmentClientQueryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 위탁 payload 의 prompt — 서킷 격리 검증에서 위탁 경로를 태우기 위한 고정값. */
    private static final Map<String, Object> MTDT = Map.of(
            "time", "NIGHT", "season", "WINTER", "weather", "RAIN",
            "terrain", "ROAD", "severity", "HIGH");

    private MockWebServer server;
    private CircuitBreakerRegistry cbRegistry;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        cbRegistry = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .slidingWindowSize(10)
                .minimumNumberOfCalls(5)
                .ignoreExceptions(NonRetryableExternalException.class)
                .build());
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    private HttpExternalAugmentClient client(RetryRegistry retryRegistry) {
        WebClient webClient = WebClient.builder().baseUrl(server.url("/").toString()).build();
        return new HttpExternalAugmentClient(webClient, cbRegistry, retryRegistry, 5);
    }

    private RetryRegistry singleAttempt() {
        return RetryRegistry.of(RetryConfig.custom().maxAttempts(1).build());
    }

    /** 프로덕션 정합 — 재시도하되 4xx 비재시도 예외는 무시. */
    private RetryRegistry tripleAttempt() {
        return RetryRegistry.of(RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ofMillis(10))
                .ignoreExceptions(NonRetryableExternalException.class)
                .build());
    }

    private void enqueueJson(int code, String body) {
        server.enqueue(new MockResponse().setResponseCode(code)
                .setHeader("Content-Type", "application/json")
                .setBody(body));
    }

    private AugmentCancelCommand cancelCommand(String jobId, String reason, boolean localTerminal) {
        TokenClaims actor = new TokenClaims("42", Role.REVIEWER, Channel.INTERNAL,
                Instant.now().plusSeconds(600));
        return new AugmentCancelCommand(jobId, actor, reason, localTerminal);
    }

    // ── §4.4 상태 조회 (INT-020) ────────────────────────────────────────────

    @Test
    @DisplayName("상태조회는_progress_와_current_step_을_파싱한다")
    void parsesStatusProgressAndStep() throws Exception {
        enqueueJson(200, """
                {"request_id":"AUG-1","job_id":"job-1","status":"RUNNING","progress":50,
                 "current_step":"INFERENCE","received_at":"2026-07-31T10:00:00Z",
                 "started_at":"2026-07-31T10:00:01Z","updated_at":"2026-07-31T10:00:05Z"}
                """);

        AugmentQueryResult<GenAiJobStatusResponse> result =
                client(singleAttempt()).fetchJobStatus("job-1").block();

        assertThat(result.isSkipped()).isFalse();
        assertThat(result.payload().status()).isEqualTo("RUNNING");
        assertThat(result.payload().progress()).isEqualTo(50);
        assertThat(result.payload().currentStep()).isEqualTo("INFERENCE");
        assertThat(result.payload().updatedAt()).isEqualTo("2026-07-31T10:00:05Z");

        RecordedRequest recorded = server.takeRequest();
        assertThat(recorded.getMethod()).isEqualTo("GET");
        assertThat(recorded.getPath()).isEqualTo("/api/genai/jobs/job-1");
    }

    @Test
    @DisplayName("상태조회는_progress_가_null_이어도_성공한다")
    void allowsNullProgress() {
        enqueueJson(200, """
                {"request_id":"AUG-2","job_id":"job-2","status":"RECEIVED",
                 "received_at":"2026-07-31T10:00:00Z","updated_at":"2026-07-31T10:00:00Z"}
                """);

        AugmentQueryResult<GenAiJobStatusResponse> result =
                client(singleAttempt()).fetchJobStatus("job-2").block();

        assertThat(result.payload().progress()).isNull();
        assertThat(result.payload().status()).isEqualTo("RECEIVED");
    }

    @Test
    @DisplayName("알_수_없는_status_값이_오면_fail_closed_로_실패한다")
    void rejectsUnknownStatus() {
        enqueueJson(200, """
                {"request_id":"AUG-3","job_id":"job-3","status":"PARTIALLY_DONE",
                 "received_at":"t","updated_at":"t"}
                """);

        assertThatThrownBy(() -> client(singleAttempt()).fetchJobStatus("job-3").block())
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.EXTERNAL_API_ERROR);
    }

    @Test
    @DisplayName("상태조회_job_id_echo_가_다르면_오배송으로_실패한다")
    void rejectsMismatchedJobIdEcho() {
        enqueueJson(200, """
                {"request_id":"AUG-4","job_id":"someone-else","status":"RUNNING",
                 "received_at":"t","updated_at":"t"}
                """);

        assertThatThrownBy(() -> client(singleAttempt()).fetchJobStatus("job-4").block())
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.EXTERNAL_API_ERROR);
    }

    @Test
    @DisplayName("progress_가_계약_범위_밖이면_fail_closed_로_실패한다")
    void rejectsOutOfRangeProgress() {
        enqueueJson(200, """
                {"request_id":"AUG-5","job_id":"job-5","status":"RUNNING","progress":250,
                 "received_at":"t","updated_at":"t"}
                """);

        assertThatThrownBy(() -> client(singleAttempt()).fetchJobStatus("job-5").block())
                .isInstanceOf(CustomException.class);
    }

    /**
     * 미등록 {@code error_code} 는 <b>판정 축이 아니다</b>(성공/실패는 status 가 정한다).
     * 응답 전체를 실패시키면 벤더가 코드를 늘릴 때 조회가 통째로 죽으므로, 원문을 보존하고
     * "미등록" 을 드러내는 것까지가 계약이다. 아는 코드처럼 흡수하지 않는 것이 핵심.
     */
    @Test
    @DisplayName("미등록_error_code_는_원문을_보존하고_미지로_표시한다")
    void preservesUnknownErrorCode() {
        enqueueJson(200, """
                {"request_id":"AUG-6","job_id":"job-6","status":"FAILED",
                 "error_code":"VENDOR_SPECIFIC_X","error_message":"boom",
                 "received_at":"t","updated_at":"t"}
                """);

        GenAiJobStatusResponse status = client(singleAttempt()).fetchJobStatus("job-6").block().payload();

        assertThat(status.errorCode()).isEqualTo("VENDOR_SPECIFIC_X");
        assertThat(status.hasKnownErrorCode()).as("미등록 코드를 아는 코드로 흡수하지 않는다").isFalse();
    }

    /**
     * DEV_FIX MED-3 — 계약 상한 초과 {@code error_code} 는 <b>입구에서</b> 끊는다(웹훅 경로와 대칭).
     *
     * <p>통과시키면 {@code LsDataAugJob.markFailed} 가 {@code ERR_CD VARCHAR(50)} 에 그대로 대입해
     * PostgreSQL {@code 22001} → 트랜잭션 롤백 → 회수 경로가 예외를 삼켜 200 → <b>그 job 이 회수 불가로
     * 고착</b>된다(만료까지 매 폴링마다 실패 트랜잭션 + 외부 호출 2회 반복). 웹훅 DTO 는
     * {@code @Size(max=50)} 으로 이미 막고 있으므로 조회 경로만 강도가 낮으면 안 된다.
     */
    @Test
    @DisplayName("벤더가_계약상한을_넘는_error_code_를_주면_계약위반으로_끊는다")
    void oversizedErrorCodeIsRejected() {
        enqueueJson(200, """
                {"request_id":"AUG-6b","job_id":"job-6b","status":"FAILED",
                 "error_code":"%s","received_at":"t","updated_at":"t"}
                """.formatted("X".repeat(GenAiJobStatusResponse.ERROR_CODE_MAX + 1)));

        assertThatThrownBy(() -> client(singleAttempt()).fetchJobStatus("job-6b").block())
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                        .isEqualTo(ErrorCode.EXTERNAL_API_ERROR))
                // 외부 원문(51자)을 사용자 메시지에 싣지 않는다(CWE-209/770)
                .hasMessageNotContaining("XXXXXXXXXX");
    }

    /** 경계 — 상한 <b>정확히</b> 50자는 통과해야 한다(과잉 차단 금지). */
    @Test
    @DisplayName("계약상한_경계길이_error_code_는_통과한다")
    void boundaryLengthErrorCodeIsAccepted() {
        String boundary = "X".repeat(GenAiJobStatusResponse.ERROR_CODE_MAX);
        enqueueJson(200, """
                {"request_id":"AUG-6c","job_id":"job-6c","status":"FAILED",
                 "error_code":"%s","received_at":"t","updated_at":"t"}
                """.formatted(boundary));

        assertThat(client(singleAttempt()).fetchJobStatus("job-6c").block().payload().errorCode())
                .isEqualTo(boundary);
    }

    @Test
    @DisplayName("벤더가_계약상한을_넘는_error_message_를_주면_계약위반으로_끊는다")
    void oversizedErrorMessageIsRejected() {
        enqueueJson(200, """
                {"request_id":"AUG-6d","job_id":"job-6d","status":"FAILED",
                 "error_code":"MODEL_EXECUTION_FAILED","error_message":"%s",
                 "received_at":"t","updated_at":"t"}
                """.formatted("m".repeat(GenAiJobStatusResponse.ERROR_MESSAGE_MAX + 1)));

        assertThatThrownBy(() -> client(singleAttempt()).fetchJobStatus("job-6d").block())
                .isInstanceOf(CustomException.class);
    }

    @Test
    @DisplayName("등록된_error_code_는_알려진_코드로_인식된다")
    void recognizesKnownErrorCode() {
        enqueueJson(200, """
                {"request_id":"AUG-7","job_id":"job-7","status":"FAILED",
                 "error_code":"MODEL_EXECUTION_FAILED","received_at":"t","updated_at":"t"}
                """);

        assertThat(client(singleAttempt()).fetchJobStatus("job-7").block().payload().hasKnownErrorCode())
                .isTrue();
    }

    // ── §4.5 결과 조회 (INT-030) ────────────────────────────────────────────

    @Test
    @DisplayName("결과조회는_results_배열_다건을_파싱한다")
    void parsesMultipleResults() throws Exception {
        enqueueJson(200, """
                {"request_id":"AUG-10","job_id":"job-10","status":"SUCCEEDED","results":[
                  {"generated_data_id":"g1","media_type":"IMAGE",
                   "output_file_path":"/nas-storage/genai/job-10/001_a_genai.jpg",
                   "checksum":"abc","media_metadata":{"mime_type":"image/jpeg","size_bytes":1024}},
                  {"generated_data_id":"g2","media_type":"VIDEO",
                   "output_file_path":"/nas-storage/genai/job-10/002_b_genai.mp4"}]}
                """);

        AugmentQueryResult<GenAiJobResultsResponse> result =
                client(singleAttempt()).fetchJobResults("job-10").block();

        assertThat(result.payload().results()).hasSize(2);
        GenAiJobResultsResponse.ResultItem first = result.payload().results().get(0);
        assertThat(first.generatedDataId()).isEqualTo("g1");
        assertThat(first.mediaType()).isEqualTo("IMAGE");
        assertThat(first.outputFilePath()).isEqualTo("/nas-storage/genai/job-10/001_a_genai.jpg");
        assertThat(first.checksum()).isEqualTo("abc");
        assertThat(first.mediaMetadata()).containsEntry("mime_type", "image/jpeg");
        // media_metadata 누락 항목은 빈 맵으로 정규화한다(소비처 NPE 방지).
        assertThat(result.payload().results().get(1).mediaMetadata()).isEmpty();

        assertThat(server.takeRequest().getPath()).isEqualTo("/api/genai/jobs/job-10/results");
    }

    @Test
    @DisplayName("결과조회가_SUCCEEDED_인데_results_가_비면_계약위반으로_실패한다")
    void rejectsSucceededWithoutResults() {
        enqueueJson(200, """
                {"request_id":"AUG-11","job_id":"job-11","status":"SUCCEEDED","results":[]}
                """);

        assertThatThrownBy(() -> client(singleAttempt()).fetchJobResults("job-11").block())
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.EXTERNAL_API_ERROR);
    }

    @Test
    @DisplayName("결과조회_status_가_SUCCEEDED_가_아니면_실패한다")
    void rejectsResultsWithNonSucceededStatus() {
        enqueueJson(200, """
                {"request_id":"AUG-12","job_id":"job-12","status":"RUNNING","results":[
                  {"generated_data_id":"g","media_type":"IMAGE","output_file_path":"/nas/x.jpg"}]}
                """);

        assertThatThrownBy(() -> client(singleAttempt()).fetchJobResults("job-12").block())
                .isInstanceOf(CustomException.class);
    }

    @Test
    @DisplayName("결과항목의_media_type_이_계약_밖이면_실패한다")
    void rejectsUnknownMediaType() {
        enqueueJson(200, """
                {"request_id":"AUG-13","job_id":"job-13","status":"SUCCEEDED","results":[
                  {"generated_data_id":"g","media_type":"AUDIO","output_file_path":"/nas/x.wav"}]}
                """);

        assertThatThrownBy(() -> client(singleAttempt()).fetchJobResults("job-13").block())
                .isInstanceOf(CustomException.class);
    }

    @Test
    @DisplayName("결과항목에_output_file_path_가_없으면_실패한다")
    void rejectsMissingOutputPath() {
        enqueueJson(200, """
                {"request_id":"AUG-14","job_id":"job-14","status":"SUCCEEDED","results":[
                  {"generated_data_id":"g","media_type":"IMAGE"}]}
                """);

        assertThatThrownBy(() -> client(singleAttempt()).fetchJobResults("job-14").block())
                .isInstanceOf(CustomException.class);
    }

    // ── §4.6 취소 (INT-031) ─────────────────────────────────────────────────

    @Test
    @DisplayName("취소요청_본문에_requested_by_가_포함된다")
    void cancelSendsRequestedBy() throws Exception {
        enqueueJson(200, """
                {"request_id":"AUG-20","job_id":"job-20","status":"CANCELED",
                 "canceled_at":"2026-07-31T11:00:00Z"}
                """);

        AugmentQueryResult<GenAiCancelResponse> result = client(singleAttempt())
                .cancelJob(cancelCommand("job-20", "오요청", false)).block();

        assertThat(result.payload().status()).isEqualTo("CANCELED");
        assertThat(result.payload().canceledAt()).isEqualTo("2026-07-31T11:00:00Z");

        RecordedRequest recorded = server.takeRequest();
        assertThat(recorded.getMethod()).isEqualTo("POST");
        assertThat(recorded.getPath()).isEqualTo("/api/genai/jobs/job-20/cancel");
        JsonNode body = MAPPER.readTree(recorded.getBody().readUtf8());
        assertThat(body.get("requested_by").asText()).isEqualTo("42");
        assertThat(body.get("reason").asText()).isEqualTo("오요청");
    }

    @Test
    @DisplayName("취소사유가_비면_reason_필드를_전송하지_않는다")
    void cancelOmitsBlankReason() throws Exception {
        enqueueJson(200, """
                {"request_id":"AUG-21","job_id":"job-21","status":"CANCELED","canceled_at":"t"}
                """);

        client(singleAttempt()).cancelJob(cancelCommand("job-21", "   ", false)).block();

        JsonNode body = MAPPER.readTree(server.takeRequest().getBody().readUtf8());
        assertThat(body.has("reason")).isFalse();
        assertThat(body.get("requested_by").asText()).isEqualTo("42");
    }

    @Test
    @DisplayName("취소_응답_status_가_CANCELED_가_아니면_실패한다")
    void rejectsCancelWithoutCanceledStatus() {
        enqueueJson(200, """
                {"request_id":"AUG-22","job_id":"job-22","status":"RUNNING","canceled_at":"t"}
                """);

        assertThatThrownBy(() -> client(singleAttempt())
                .cancelJob(cancelCommand("job-22", null, false)).block())
                .isInstanceOf(CustomException.class);
    }

    @Test
    @DisplayName("이미_종결된_job_은_취소_시_외부_호출을_하지_않는다")
    void skipsCancelWhenLocallyTerminal() {
        AugmentQueryResult<GenAiCancelResponse> result = client(singleAttempt())
                .cancelJob(cancelCommand("job-23", "취소", true)).block();

        assertThat(result.isSkipped()).isTrue();
        assertThat(result.skipReason()).isEqualTo(AugmentQueryResult.SkipReason.LOCAL_TERMINAL);
        assertThat(result.payload()).isNull();
        assertThat(server.getRequestCount()).as("종결 job 은 409 왕복을 만들지 않는다").isZero();
    }

    @Test
    @DisplayName("취소_요청자가_인증주체가_아니면_커맨드_생성이_거부된다")
    void rejectsCancelWithoutActor() {
        assertThatThrownBy(() -> new AugmentCancelCommand("job-24", null, "r", false))
                .isInstanceOf(CustomException.class);
    }

    // ── 4xx 비재시도 / 서킷 격리 ─────────────────────────────────────────────

    @Test
    @DisplayName("취소가_409_면_재시도하지_않고_결정적_실패로_분류된다")
    void cancel409IsNonRetryable() {
        enqueueJson(409, "{\"code\":\"STATE_CONFLICT\",\"message\":\"이미 종료된 작업\"}");

        assertThatThrownBy(() -> client(tripleAttempt())
                .cancelJob(cancelCommand("job-30", null, false)).block())
                .isInstanceOf(NonRetryableExternalException.class);

        assertThat(server.getRequestCount()).as("409 는 1회만 전송").isEqualTo(1);
    }

    /**
     * DEV_FIX MED-7 — 4xx 상태 코드를 <b>값으로</b> 보존한다.
     *
     * <p>소비 계층(취소)이 409({@code STATE_CONFLICT})·404({@code JOB_NOT_FOUND})를 "외부에 취소할 대상
     * 없음(=사실상 성공)" 으로, 타임아웃·5xx 를 "전달 실패" 로 갈라야 하는데 예외 <b>메시지 문자열</b>로만
     * 상태를 실으면 파싱에 의존하게 되어 문구 변경에 조용히 오분류된다.
     */
    @Test
    @DisplayName("4xx_예외는_상태코드를_값으로_보존한다")
    void nonRetryableCarriesStatusCode() {
        enqueueJson(409, "{\"code\":\"STATE_CONFLICT\"}");

        assertThatThrownBy(() -> client(singleAttempt())
                .cancelJob(cancelCommand("job-30b", null, false)).block())
                .isInstanceOf(NonRetryableExternalException.class)
                .satisfies(e -> assertThat(((NonRetryableExternalException) e).getStatusCode())
                        .isEqualTo(409));
    }

    @Test
    @DisplayName("404_는_재시도하지_않는다")
    void notFoundIsNonRetryable() {
        enqueueJson(404, "{\"code\":\"JOB_NOT_FOUND\",\"message\":\"작업을 찾을 수 없습니다\"}");

        assertThatThrownBy(() -> client(tripleAttempt()).fetchJobStatus("job-31").block())
                .isInstanceOf(NonRetryableExternalException.class);

        assertThat(server.getRequestCount()).as("404 는 1회만 전송").isEqualTo(1);
    }

    @Test
    @DisplayName("조회_4xx_는_서킷_failure_로_집계되지_않는다")
    void nonRetryable4xxIsNotCircuitFailure() {
        enqueueJson(409, "{\"code\":\"STATE_CONFLICT\"}");

        assertThatThrownBy(() -> client(tripleAttempt()).fetchJobResults("job-32").block())
                .isInstanceOf(NonRetryableExternalException.class);

        CircuitBreaker queryCb = cbRegistry.circuitBreaker(HttpExternalAugmentClient.QUERY_RESILIENCE_NAME);
        assertThat(queryCb.getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test
    @DisplayName("조회용_서킷이_열려도_위탁은_영향받지_않는다")
    void openQueryCircuitDoesNotBlockSubmit() {
        // 조회용 서킷만 강제 OPEN — 폴링 장애가 신규 위탁을 막지 못해야 한다.
        cbRegistry.circuitBreaker(HttpExternalAugmentClient.QUERY_RESILIENCE_NAME)
                .transitionToOpenState();
        server.enqueue(new MockResponse().setResponseCode(202)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"request_id":"AUG-40","job_id":"job-40","status":"RECEIVED",
                         "received_at":"2026-07-31T10:00:00Z"}
                        """));
        HttpExternalAugmentClient client = client(singleAttempt());

        AugmentSubmitResult submitted = client.requestAugment(new AugmentSubmitCommand(
                10L, "WINTER", MTDT, null, "AUG-40", "FLOOD", null, "1",
                "http://localhost:8080/api/v1/genai/callback",
                List.of(new AugmentInputFile(1, "/app/storage/deidentified/frames/1.jpg")),
                1, 1)).block();

        assertThat(submitted.externalJobId()).as("위탁 서킷은 독립이라 그대로 성공한다").isEqualTo("job-40");
        // 반대 방향도 확인 — 조회는 열린 서킷에 막힌다(격리가 "둘 다 안 막힘" 이 아님을 고정).
        assertThatThrownBy(() -> client.fetchJobStatus("job-40").block())
                .isInstanceOf(CallNotPermittedException.class);
        assertThat(cbRegistry.circuitBreaker("augmentClient").getState())
                .isEqualTo(CircuitBreaker.State.CLOSED);
    }

    // ── fail-fast job_id (S13) ──────────────────────────────────────────────

    @Test
    @DisplayName("externalJobId_가_null_이면_외부_호출_없이_즉시_실패한다")
    void failsFastOnNullJobId() {
        assertThatThrownBy(() -> client(singleAttempt()).fetchJobStatus(null).block())
                .isInstanceOf(CustomException.class);
        assertThatThrownBy(() -> client(singleAttempt()).fetchJobResults("  ").block())
                .isInstanceOf(CustomException.class);
        assertThatThrownBy(() -> client(singleAttempt())
                .cancelJob(cancelCommand(null, null, false)).block())
                .isInstanceOf(CustomException.class);

        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    @DisplayName("externalJobId_가_계약_형식이_아니면_외부_호출_없이_거부된다")
    void failsFastOnMalformedJobId() {
        // 경로 세그먼트로 들어가는 값이라 형식 검증이 곧 경로 조작 방어다(CWE-22).
        assertThatThrownBy(() -> client(singleAttempt()).fetchJobStatus("../../secret").block())
                .isInstanceOf(CustomException.class);
        assertThatThrownBy(() -> client(singleAttempt()).fetchJobResults("job 1").block())
                .isInstanceOf(CustomException.class);

        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    @DisplayName("조회_응답이_비어있으면_EXTERNAL_API_ERROR")
    void rejectsEmptyBody() {
        server.enqueue(new MockResponse().setResponseCode(200));

        assertThatThrownBy(() -> client(singleAttempt()).fetchJobStatus("job-50").block())
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.EXTERNAL_API_ERROR);
    }

    @Test
    @DisplayName("조회는_구독하기_전에는_외부_호출을_개시하지_않는다")
    void queriesAreColdUntilSubscribed() {
        enqueueJson(200, """
                {"request_id":"AUG-60","job_id":"job-60","status":"RUNNING",
                 "received_at":"t","updated_at":"t"}
                """);

        client(singleAttempt()).fetchJobStatus("job-60");

        assertThat(server.getRequestCount()).isZero();
    }

    // ── 디코딩 실패 분류 (DEV_FIX MED-1a) ─────────────────────────────────────

    /**
     * 디코딩은 {@code bodyToMono(...)} <b>안</b>, 즉 재시도/서킷 연산자보다 <b>앞</b>에서 일어난다.
     * 그래서 계약 검증(연산자 뒤)과 달리 <b>재시도·서킷 집계 대상</b>이었다. 그러나 깨진 본문은
     * 다시 요청해도 같은 결과라 왕복만 늘고, 반복되면 폴링 서킷을 열어 조회 전체를 죽인다.
     */
    @Test
    @DisplayName("디코딩_실패는_재시도되지_않고_서킷에_집계되지_않는다")
    void decodingFailureIsNonRetryableAndNotCircuitFailure() {
        // given: results[null] → List.copyOf NPE → Jackson ValueInstantiationException(결정적 실패)
        enqueueJson(200, """
                {"request_id":"AUG-70","job_id":"job-70","status":"SUCCEEDED","results":[null]}
                """);

        // when
        assertThatThrownBy(() -> client(tripleAttempt()).fetchJobResults("job-70").block())
                .isInstanceOf(NonRetryableExternalException.class);

        // then: 1회만 전송 + 서킷 failure 미집계
        assertThat(server.getRequestCount()).as("깨진 본문은 재요청해도 같다").isEqualTo(1);
        assertThat(cbRegistry.circuitBreaker(HttpExternalAugmentClient.QUERY_RESILIENCE_NAME)
                .getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test
    @DisplayName("숫자_범위_초과_progress_도_결정적_디코딩_실패로_분류된다")
    void numericOverflowIsNonRetryable() {
        // given: int 범위를 넘는 progress → InputCoercionException
        enqueueJson(200, """
                {"request_id":"AUG-71","job_id":"job-71","status":"RUNNING","progress":99999999999999,
                 "received_at":"t","updated_at":"t"}
                """);

        assertThatThrownBy(() -> client(tripleAttempt()).fetchJobStatus("job-71").block())
                .isInstanceOf(NonRetryableExternalException.class);

        assertThat(server.getRequestCount()).isEqualTo(1);
        assertThat(cbRegistry.circuitBreaker(HttpExternalAugmentClient.QUERY_RESILIENCE_NAME)
                .getMetrics().getNumberOfFailedCalls()).isZero();
    }

    /**
     * 계약 밖 Content-Type 은 WebClient 가 {@code UnsupportedMediaTypeException} 을
     * <b>{@code WebClientResponseException}(200) 으로 감싸서</b> 던진다(실측). 최상위 타입만 보는
     * 분류는 이 케이스를 놓쳐 재시도로 흘리므로, 분류는 <b>원인 체인</b>을 훑어야 한다.
     */
    @Test
    @DisplayName("비_JSON_본문도_결정적_디코딩_실패로_분류된다")
    void unsupportedMediaTypeIsNonRetryable() {
        // given: Content-Type 이 계약과 다르면 디코더를 찾지 못한다(UnsupportedMediaTypeException)
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "text/html")
                .setBody("<html>gateway</html>"));

        assertThatThrownBy(() -> client(tripleAttempt()).fetchJobStatus("job-72").block())
                .isInstanceOf(NonRetryableExternalException.class);

        assertThat(server.getRequestCount()).isEqualTo(1);
        assertThat(cbRegistry.circuitBreaker(HttpExternalAugmentClient.QUERY_RESILIENCE_NAME)
                .getMetrics().getNumberOfFailedCalls()).isZero();
    }

    /** 외부 원문(벤더가 통제하는 문자열)은 사용자 응답 message 로 흘리지 않는다 (CWE-209/770). */
    @Test
    @DisplayName("계약위반_예외_메시지에_외부_원문이_실리지_않는다")
    void contractViolationMessageOmitsExternalText() {
        String hugeStatus = "A".repeat(5_000);
        enqueueJson(200, """
                {"request_id":"AUG-73","job_id":"job-73","status":"%s",
                 "received_at":"t","updated_at":"t"}
                """.formatted(hugeStatus));

        assertThatThrownBy(() -> client(singleAttempt()).fetchJobStatus("job-73").block())
                .isInstanceOf(CustomException.class)
                .satisfies(e -> {
                    assertThat(e.getMessage()).doesNotContain("AAAA");
                    assertThat(e.getMessage().length()).isLessThan(200);
                });
    }

    // ── 취소 서킷 분리 (DEV_FIX MED-1b) ───────────────────────────────────────

    /**
     * 취소는 <b>사용자 능동 행위</b>다. 자동 폴링(상태·결과 조회)이 벤더 장애로 서킷을 열었다고 해서
     * 사용자의 취소까지 30초 단위로 막히면 안 된다 — 그래서 취소는 전용 인스턴스를 탄다.
     */
    @Test
    @DisplayName("폴링_서킷이_열려도_취소는_가능하다")
    void openPollingCircuitDoesNotBlockCancel() {
        // given: 폴링용 서킷만 강제 OPEN
        cbRegistry.circuitBreaker(HttpExternalAugmentClient.QUERY_RESILIENCE_NAME)
                .transitionToOpenState();
        enqueueJson(200, """
                {"request_id":"AUG-80","job_id":"job-80","status":"CANCELED","canceled_at":"t"}
                """);
        HttpExternalAugmentClient client = client(singleAttempt());

        // when
        AugmentQueryResult<GenAiCancelResponse> canceled =
                client.cancelJob(cancelCommand("job-80", null, false)).block();

        // then: 취소는 자기 서킷(augmentCancel)을 타므로 성공한다
        assertThat(canceled.payload().status()).isEqualTo("CANCELED");
        // 반대 방향 — 폴링은 여전히 열린 서킷에 막힌다(격리가 "둘 다 안 막힘" 이 아님을 고정)
        assertThatThrownBy(() -> client.fetchJobStatus("job-80").block())
                .isInstanceOf(CallNotPermittedException.class);
        assertThat(cbRegistry.circuitBreaker(HttpExternalAugmentClient.CANCEL_RESILIENCE_NAME)
                .getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("취소_서킷_인스턴스명은_폴링_위탁과_모두_다르다")
    void cancelResilienceInstanceIsDistinct() {
        assertThat(HttpExternalAugmentClient.CANCEL_RESILIENCE_NAME)
                .isNotEqualTo(HttpExternalAugmentClient.QUERY_RESILIENCE_NAME)
                .isNotEqualTo(HttpExternalAugmentClient.SUBMIT_RESILIENCE_NAME);
    }

    @Test
    @DisplayName("조회_5xx_는_재시도한다")
    void retriesOn5xx() {
        server.enqueue(new MockResponse().setResponseCode(500));
        enqueueJson(200, """
                {"request_id":"AUG-61","job_id":"job-61","status":"RUNNING",
                 "received_at":"t","updated_at":"t"}
                """);

        AugmentQueryResult<GenAiJobStatusResponse> result =
                client(tripleAttempt()).fetchJobStatus("job-61").block();

        assertThat(result.payload().status()).isEqualTo("RUNNING");
        assertThat(server.getRequestCount()).isEqualTo(2);
    }
}
