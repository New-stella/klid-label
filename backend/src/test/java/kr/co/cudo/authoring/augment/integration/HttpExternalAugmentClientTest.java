package kr.co.cudo.authoring.augment.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import kr.co.cudo.authoring.common.client.NonRetryableExternalException;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * HttpExternalAugmentClient 단위 테스트 — 「생성형 AI API 연동명세서 v1.1」 §4.1 계약 정합.
 *
 * <p>목 서버(mock-server/app/routers/augment.py)가 계약 정본이므로, 그 스키마·헤더·응답 규약을
 * 그대로 단언한다.
 */
class HttpExternalAugmentClientTest {

    /** 위탁 payload 의 prompt — 이 테스트의 관심사가 아니라 계약(필수 non-empty)을 채우는 고정값. */
    private static final java.util.Map<String, Object> MTDT = java.util.Map.of("time", "NIGHT", "season", "WINTER", "weather", "RAIN", "terrain", "ROAD", "severity", "HIGH");


    private static final ObjectMapper MAPPER = new ObjectMapper();

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

    /** 프로덕션 정합 — 3회 재시도하되 4xx 비재시도 예외는 무시. */
    private RetryRegistry tripleAttempt() {
        return RetryRegistry.of(RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ofMillis(10))
                .ignoreExceptions(NonRetryableExternalException.class)
                .build());
    }

    private AugmentSubmitCommand command(String requestId) {
        return new AugmentSubmitCommand(
                10L, "AUGMENT", MTDT, null, requestId, "1",
                "http://localhost:8080/api/v1/genai/callback",
                List.of(new AugmentInputFile(1, "/app/storage/deidentified/frames/1.jpg"),
                        new AugmentInputFile(2, "/app/storage/deidentified/frames/2.jpg")),
                1, 1);
    }

    private void enqueueAccepted(String requestId, String jobId) {
        server.enqueue(new MockResponse()
                .setResponseCode(202)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"request_id":"%s","job_id":"%s","status":"RECEIVED",
                         "received_at":"2026-07-28T10:00:00"}
                        """.formatted(requestId, jobId)));
    }

    /**
     * 자유 지시문은 <b>최상위 문자열</b>로 나가며 {@code mtdt} 안으로 접히지 않는다.
     *
     * <p>구 시험은 침수 세부 유형({@code evnt_subtype})이 함께 나가는 것도 고정했으나, 그 값은
     * 2026-09-02 이후 <b>어떤 경우에도 전송하지 않는다</b>({@code @design ADR-059}) — 요청 바디
     * 레코드에 필드 자체가 없다.
     */
    @Test
    @DisplayName("자유지시문은_최상위_문자열_필드로_나가고_세부유형은_전송되지_않는다")
    void optionalFieldsAreSentAtTopLevel() throws Exception {
        enqueueAccepted("AUG-3", "job-3");

        client(singleAttempt()).requestAugment(new AugmentSubmitCommand(
                10L, "AUGMENT", MTDT, "도로 구조를 유지해줘", "AUG-3", "1",
                "http://localhost:8080/api/v1/genai/callback",
                List.of(new AugmentInputFile(1, "/app/storage/deidentified/frames/1.jpg")),
                1, 1)).block();

        JsonNode body = MAPPER.readTree(server.takeRequest().getBody().readUtf8());
        assertThat(body.get("prompt").isTextual())
                .as("자유 지시문은 문자열이다 — 객체면 벤더가 400 INVALID_PARAMETER 로 거부한다")
                .isTrue();
        assertThat(body.get("prompt").asText()).isEqualTo("도로 구조를 유지해줘");
        assertThat(body.has("evnt_subtype"))
                .as("세부 유형은 어떤 경우에도 전송하지 않는다(ADR-059)")
                .isFalse();
        assertThat(body.get("mtdt").has("prompt")).isFalse();
    }

    @Test
    @DisplayName("증강_요청시_외부_genai_jobs_엔드포인트로_실제_POST_가_전송됨")
    void postsToGenAiJobsEndpoint() throws Exception {
        enqueueAccepted("AUG-1", "job-abc");

        client(singleAttempt()).requestAugment(command("AUG-1")).block();

        RecordedRequest recorded = server.takeRequest();
        assertThat(recorded.getMethod()).isEqualTo("POST");
        assertThat(recorded.getPath()).isEqualTo("/api/genai/jobs");
    }

    /**
     * ★ v1.3 요청 바디 — 생성 조건은 최상위 {@code mtdt} 객체, 자유 지시문은 별개 {@code prompt}
     * <b>문자열</b>이다.
     *
     * <p>구 계약(v1.1)은 5항목을 {@code prompt} 객체로 실었고 그보다 더 앞선 구현은 증강 유형별 고정
     * 문구({@code style}/{@code instruction}/{@code preserve})를 서버가 만들어 보냈다. 둘 다 폐기됐다 —
     * 명세가 <i>"prompt 값으로 객체를 전달하지 않습니다"</i> 를 명시하며 객체를 실으면
     * {@code 400 INVALID_PARAMETER} 다. 이 시험이 그 회귀를 고정한다.
     */
    @Test
    @DisplayName("요청_바디가_명세서_v1_3_스키마와_일치함")
    void requestBodyMatchesContract() throws Exception {
        enqueueAccepted("AUG-2", "job-2");

        client(singleAttempt()).requestAugment(command("AUG-2")).block();

        JsonNode body = MAPPER.readTree(server.takeRequest().getBody().readUtf8());
        assertThat(body.get("request_id").asText()).isEqualTo("AUG-2");
        assertThat(body.get("request_channel").asText()).isEqualTo("AUTHORING");
        assertThat(body.get("operation_type").asText()).isEqualTo("AUGMENT");
        assertThat(body.get("generation_mode").asText()).isEqualTo("I2I");
        // ★ 이벤트 유형은 <서버가 고정 송신하는 중립값>이다 (@design ADR-059).
        //   요청자도 커맨드도 이 값을 정하지 않는다 — 커맨드에 그 필드가 아예 없다.
        //   ⚠ 보유 규격서 판본(v1.3)의 허용값은 FLOOD | WILDFIRE 라 이 값이 문서에는 없다.
        //     벤더와 협의가 끝난 값이므로 그 문서만 보고 되돌리지 말 것(개정판 수령 시 대조).
        assertThat(body.get("evnt_type").asText()).isEqualTo("ETC");
        assertThat(body.get("request_user_id").asText()).isEqualTo("1");
        assertThat(body.get("callback_url").asText()).endsWith("/v1/genai/callback");

        JsonNode mtdt = body.get("mtdt");
        assertThat(mtdt.isObject()).as("생성 조건은 최상위 mtdt 객체다(v1.3)").isTrue();
        assertThat(mtdt.get("time").asText()).isEqualTo("NIGHT");
        assertThat(mtdt.get("season").asText()).isEqualTo("WINTER");
        assertThat(mtdt.get("weather").asText()).isEqualTo("RAIN");
        assertThat(mtdt.get("terrain").asText()).isEqualTo("ROAD");
        assertThat(mtdt.get("severity").asText()).isEqualTo("HIGH");
        assertThat(mtdt.has("style")).as("서버 고정 문구 시절 키가 남아 있으면 안 된다").isFalse();
        assertThat(mtdt.has("instruction")).isFalse();

        // 자유 지시문은 <선택>이라 없으면 키 자체가 나가지 않는다.
        assertThat(body.has("prompt"))
                .as("prompt 를 객체로 되돌리는 회귀 차단 — 없을 때는 키 자체가 없어야 한다")
                .isFalse();
        // 침수 세부 유형은 <선택>이 아니라 <항상 미전송>이다 (@design ADR-059).
        assertThat(body.has("evnt_subtype")).isFalse();
        assertThat(body.has("condition")).as("구 계약의 최상위 condition 은 제외됐다").isFalse();

        JsonNode files = body.get("input_files");
        assertThat(files.isArray()).isTrue();
        assertThat(files).hasSize(2);
        // sequence 는 1부터 (§4.1)
        assertThat(files.get(0).get("sequence").asInt()).isEqualTo(1);
        assertThat(files.get(1).get("sequence").asInt()).isEqualTo(2);
        assertThat(files.get(0).get("file_path").asText())
                .isEqualTo("/app/storage/deidentified/frames/1.jpg");
        // 외부가 발급하는 값(job_id)은 우리가 보내지 않는다.
        assertThat(body.has("job_id")).isFalse();
        assertThat(body.has("external_job_id")).isFalse();
    }

    @Test
    @DisplayName("Idempotency_Key_헤더가_우리_request_id_로_전송됨")
    void sendsIdempotencyKeyHeader() throws Exception {
        enqueueAccepted("AUG-3", "job-3");

        client(singleAttempt()).requestAugment(command("AUG-3")).block();

        RecordedRequest recorded = server.takeRequest();
        assertThat(recorded.getHeader("Idempotency-Key")).isEqualTo("AUG-3");
        // 인증 헤더는 붙이지 않는다(명세서·목 모두 인증 미구현이 확정 계약).
        assertThat(recorded.getHeader("Authorization")).isNull();
        assertThat(recorded.getHeader("x-access-token")).isNull();
    }

    @Test
    @DisplayName("외부가_준_job_id_가_결과로_반환된다")
    void returnsExternallyIssuedJobId() {
        enqueueAccepted("AUG-4", "external-job-4");

        AugmentSubmitResult result = client(singleAttempt()).requestAugment(command("AUG-4")).block();

        assertThat(result.externalJobId()).isEqualTo("external-job-4");
        assertThat(result.status()).isEqualTo(AugmentSubmitResult.STATUS_RECEIVED);
    }

    /**
     * Phase C-3 — 반환 {@link reactor.core.publisher.Mono} 는 <b>cold</b> 여야 한다.
     *
     * <p>반환 즉시 호출이 나가면 재시도/취소 의미론이 깨지고, 조립만 하고 버린 요청이 실제로 외부에
     * 도달해 <b>중복 위탁</b>이 된다. 호출부는 청크를 직렬로 <b>하나씩 구독</b>하므로 이 성질이 곧
     * "앞 청크가 끝나기 전에 다음 청크가 나가지 않는다" 의 전제다.
     */
    @Test
    @DisplayName("구독하기_전에는_외부_호출이_개시되지_않는다")
    void isColdUntilSubscribed() {
        enqueueAccepted("AUG-COLD", "job-cold");

        client(singleAttempt()).requestAugment(command("AUG-COLD"));

        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    @DisplayName("응답이_비어있으면_EXTERNAL_API_ERROR")
    void rejectsEmptyBody() {
        // 202 + 본문 없음 — 어느 핸들러도 타지 않는 "신호 없는 종료" 를 실패로 승격한다.
        server.enqueue(new MockResponse().setResponseCode(202));

        assertThatThrownBy(() -> client(singleAttempt()).requestAugment(command("AUG-11")).block())
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.EXTERNAL_API_ERROR);
    }

    @Test
    @DisplayName("4xx_응답은_재시도하지_않는다")
    void doesNotRetryOn4xx() {
        server.enqueue(new MockResponse().setResponseCode(400)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":\"INVALID_PARAMETER\",\"message\":\"bad\"}"));

        assertThatThrownBy(() -> client(tripleAttempt()).requestAugment(command("AUG-5")).block())
                .isInstanceOf(NonRetryableExternalException.class);

        assertThat(server.getRequestCount()).as("4xx 는 1회만 전송(중복 위탁 방지)").isEqualTo(1);
    }

    @Test
    @DisplayName("5xx_응답은_재시도한다")
    void retriesOn5xx() {
        server.enqueue(new MockResponse().setResponseCode(500));
        server.enqueue(new MockResponse().setResponseCode(500));
        enqueueAccepted("AUG-6", "job-6");

        AugmentSubmitResult result = client(tripleAttempt()).requestAugment(command("AUG-6")).block();

        assertThat(result.externalJobId()).isEqualTo("job-6");
        assertThat(server.getRequestCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("연결실패는_재시도한다")
    void retriesOnConnectionFailure() throws IOException {
        server.shutdown(); // 연결 자체가 실패하는 상황

        assertThatThrownBy(() -> client(tripleAttempt()).requestAugment(command("AUG-7")).block())
                .isInstanceOf(Exception.class);
        // 재시도 자체는 Resilience4j 가 수행한다(서버가 없어 요청 카운트로 셀 수 없음).
        // 여기서는 4xx 와 달리 NonRetryableExternalException 으로 분류되지 않음을 단언한다.
    }

    @Test
    @DisplayName("응답의_request_id_가_다르면_EXTERNAL_API_ERROR")
    void rejectsMismatchedRequestIdEcho() {
        enqueueAccepted("SOMEONE-ELSE", "job-8");

        assertThatThrownBy(() -> client(singleAttempt()).requestAugment(command("AUG-8")).block())
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.EXTERNAL_API_ERROR);
    }

    @Test
    @DisplayName("응답에_job_id_가_없으면_EXTERNAL_API_ERROR")
    void rejectsMissingJobId() {
        server.enqueue(new MockResponse().setResponseCode(202)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"request_id\":\"AUG-9\",\"status\":\"RECEIVED\"}"));

        assertThatThrownBy(() -> client(singleAttempt()).requestAugment(command("AUG-9")).block())
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.EXTERNAL_API_ERROR);
    }

    @Test
    @DisplayName("응답_status_가_RECEIVED_가_아니면_EXTERNAL_API_ERROR")
    void rejectsUnexpectedStatus() {
        server.enqueue(new MockResponse().setResponseCode(202)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"request_id\":\"AUG-10\",\"job_id\":\"j\",\"status\":\"FAILED\"}"));

        assertThatThrownBy(() -> client(singleAttempt()).requestAugment(command("AUG-10")).block())
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.EXTERNAL_API_ERROR);
    }
}
