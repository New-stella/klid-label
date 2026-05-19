package kr.co.cudo.authoring.common.client;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import kr.co.cudo.authoring.common.client.dto.VlmTimeseriesRequest;
import kr.co.cudo.authoring.common.client.dto.VlmTimeseriesResponse;
import kr.co.cudo.authoring.common.exception.CustomException;
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
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * VlmClient 단위 테스트 — Phase 1 (HIGH 시나리오 S-1/S-4 방어 포함).
 */
class VlmClientTest {

    private MockWebServer server;
    private CircuitBreakerRegistry cbRegistry;
    private RetryRegistry retryRegistry;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        cbRegistry = CircuitBreakerRegistry.of(
                CircuitBreakerConfig.custom()
                        .failureRateThreshold(50)
                        .slidingWindowSize(10)
                        .minimumNumberOfCalls(5)
                        .build());
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    private WebClient webClient() {
        return WebClient.builder().baseUrl(server.url("/").toString()).build();
    }

    /** 재시도 영향 없는 single-attempt registry (S-1 멱등 키 검증용). */
    private RetryRegistry singleAttempt() {
        return RetryRegistry.of(RetryConfig.custom().maxAttempts(1).build());
    }

    /** Retry 3회 + 짧은 wait 로 S-4 검증용. */
    private RetryRegistry tripleAttemptShort() {
        return RetryRegistry.of(RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ofMillis(10))
                .retryExceptions(RuntimeException.class)
                .build());
    }

    @Test
    @DisplayName("VlmClient_위탁_요청_시_idempotencyKey_헤더와_페이로드가_동일하게_포함됨")
    void idempotencyKeyHeaderIncluded() throws InterruptedException {
        // given — enabled=true 로 클라이언트 구성, 정상 응답 stub
        retryRegistry = singleAttempt();
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"externalJobId\":\"job-001\",\"idempotencyKey\":\"key-abc\",\"status\":\"ACCEPTED\"}"));
        VlmClient client = new VlmClient(webClient(), cbRegistry, retryRegistry, true, 5L);

        // when — 호출자가 idempotencyKey 지정
        VlmTimeseriesResponse resp = client.submitTimeseries(
                        new VlmTimeseriesRequest(42L, "s3://bucket/video.mp4", "key-abc", "https://cb"))
                .block(Duration.ofSeconds(2));

        // then — 헤더와 응답 모두 동일 멱등 키
        assertThat(resp).isNotNull();
        assertThat(resp.status()).isEqualTo("ACCEPTED");
        RecordedRequest recorded = server.takeRequest(2, TimeUnit.SECONDS);
        assertThat(recorded).isNotNull();
        assertThat(recorded.getHeader(VlmClient.IDEMPOTENCY_HEADER)).isEqualTo("key-abc");
        assertThat(recorded.getPath()).isEqualTo(VlmClient.SUBMIT_PATH);
        // 페이로드에도 키 포함
        assertThat(recorded.getBody().readUtf8()).contains("\"idempotencyKey\":\"key-abc\"");
    }

    @Test
    @DisplayName("VlmClient_호출자가_idempotencyKey_null_제공_시_UUID_자동발급")
    void idempotencyKeyAutoIssuedWhenNull() throws InterruptedException {
        retryRegistry = singleAttempt();
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"externalJobId\":\"job-002\",\"idempotencyKey\":\"x\",\"status\":\"ACCEPTED\"}"));
        VlmClient client = new VlmClient(webClient(), cbRegistry, retryRegistry, true, 5L);

        client.submitTimeseries(
                        new VlmTimeseriesRequest(43L, "s3://b/v.mp4", null, null))
                .block(Duration.ofSeconds(2));

        RecordedRequest recorded = server.takeRequest(2, TimeUnit.SECONDS);
        assertThat(recorded).isNotNull();
        String header = recorded.getHeader(VlmClient.IDEMPOTENCY_HEADER);
        assertThat(header).isNotNull().isNotBlank();
        // UUID 형식 (8-4-4-4-12)
        assertThat(header).matches("^[0-9a-fA-F-]{36}$");
    }

    @Test
    @DisplayName("VlmClient_enabled_false_시_외부_호출_없이_SKIPPED_반환_NO_OP")
    void enabledFalseReturnsSkippedWithoutCall() throws InterruptedException {
        // given — enabled=false
        retryRegistry = singleAttempt();
        VlmClient client = new VlmClient(webClient(), cbRegistry, retryRegistry, false, 5L);

        // when
        VlmTimeseriesResponse resp = client.submitTimeseries(
                        new VlmTimeseriesRequest(44L, "s3://b/v.mp4", null, null))
                .block(Duration.ofSeconds(2));

        // then — 외부 호출 0건, SKIPPED 상태, idempotencyKey 는 발급되어 있음
        assertThat(resp).isNotNull();
        assertThat(resp.status()).isEqualTo("SKIPPED");
        assertThat(resp.externalJobId()).isNull();
        assertThat(resp.idempotencyKey()).isNotBlank();
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    @DisplayName("VlmClient_타임아웃_시_재시도_3회_후_예외_전파")
    void retryThreeTimesOnFailure() {
        // given — enabled=true, retry 3회, 매 호출 500 반환
        retryRegistry = tripleAttemptShort();
        for (int i = 0; i < 3; i++) {
            server.enqueue(new MockResponse().setResponseCode(500));
        }
        VlmClient client = new VlmClient(webClient(), cbRegistry, retryRegistry, true, 5L);

        // when / then — 최종 예외 전파
        assertThatThrownBy(() -> client.submitTimeseries(
                        new VlmTimeseriesRequest(45L, "s3://b/v.mp4", "key-fail", null))
                .block(Duration.ofSeconds(5)))
                .isInstanceOf(RuntimeException.class);

        // 재시도 3회 발생 확인
        assertThat(server.getRequestCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("VlmClient_isEnabled_토글_상태_노출")
    void isEnabledExposesToggle() {
        retryRegistry = singleAttempt();
        VlmClient disabled = new VlmClient(webClient(), cbRegistry, retryRegistry, false, 5L);
        VlmClient enabled = new VlmClient(webClient(), cbRegistry, retryRegistry, true, 5L);
        assertThat(disabled.isEnabled()).isFalse();
        assertThat(enabled.isEnabled()).isTrue();
    }

    // ---- DEV_FIX-1 차 보강 ----

    @Test
    @DisplayName("VlmClient_응답_externalJobId_너무_길면_EXTERNAL_API_ERROR")
    void responseExternalJobIdTooLongRejected() {
        // given — 129자 externalJobId (allowlist 128 초과)
        retryRegistry = singleAttempt();
        String tooLong = "a".repeat(129);
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"externalJobId\":\"" + tooLong + "\",\"idempotencyKey\":\"k\",\"status\":\"ACCEPTED\"}"));
        VlmClient client = new VlmClient(webClient(), cbRegistry, retryRegistry, true, 5L);

        // when / then
        assertThatThrownBy(() -> client.submitTimeseries(
                        new VlmTimeseriesRequest(50L, "s3://b/v.mp4", "k", null))
                .block(Duration.ofSeconds(2)))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("VLM");
    }

    @Test
    @DisplayName("VlmClient_응답_externalJobId_패턴_위반_시_EXTERNAL_API_ERROR")
    void responseExternalJobIdInvalidPatternRejected() {
        retryRegistry = singleAttempt();
        // 공백/특수문자 포함 — 패턴 [A-Za-z0-9_-]+ 위반
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"externalJobId\":\"job 001 <script>\",\"idempotencyKey\":\"k\",\"status\":\"ACCEPTED\"}"));
        VlmClient client = new VlmClient(webClient(), cbRegistry, retryRegistry, true, 5L);

        assertThatThrownBy(() -> client.submitTimeseries(
                        new VlmTimeseriesRequest(51L, "s3://b/v.mp4", "k", null))
                .block(Duration.ofSeconds(2)))
                .isInstanceOf(CustomException.class);
    }

    @Test
    @DisplayName("VlmClient_응답_status_허용목록_외_값이면_EXTERNAL_API_ERROR")
    void responseStatusNotInWhitelistRejected() {
        retryRegistry = singleAttempt();
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"externalJobId\":\"job-001\",\"idempotencyKey\":\"k\",\"status\":\"HACKED\"}"));
        VlmClient client = new VlmClient(webClient(), cbRegistry, retryRegistry, true, 5L);

        assertThatThrownBy(() -> client.submitTimeseries(
                        new VlmTimeseriesRequest(52L, "s3://b/v.mp4", "k", null))
                .block(Duration.ofSeconds(2)))
                .isInstanceOf(CustomException.class);
    }

    @Test
    @DisplayName("VlmClient_응답_externalJobId_빈문자열_시_EXTERNAL_API_ERROR")
    void responseExternalJobIdBlankRejected() {
        retryRegistry = singleAttempt();
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"externalJobId\":\"\",\"idempotencyKey\":\"k\",\"status\":\"ACCEPTED\"}"));
        VlmClient client = new VlmClient(webClient(), cbRegistry, retryRegistry, true, 5L);

        assertThatThrownBy(() -> client.submitTimeseries(
                        new VlmTimeseriesRequest(53L, "s3://b/v.mp4", "k", null))
                .block(Duration.ofSeconds(2)))
                .isInstanceOf(CustomException.class);
    }

    @Test
    @DisplayName("VlmClient_응답_unknown_필드_있어도_역직렬화_통과")
    void responseUnknownFieldsIgnored() {
        retryRegistry = singleAttempt();
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"externalJobId\":\"job-99\",\"idempotencyKey\":\"k\",\"status\":\"ACCEPTED\",\"extraEvil\":\"<script>\"}"));
        VlmClient client = new VlmClient(webClient(), cbRegistry, retryRegistry, true, 5L);

        VlmTimeseriesResponse resp = client.submitTimeseries(
                        new VlmTimeseriesRequest(54L, "s3://b/v.mp4", "k", null))
                .block(Duration.ofSeconds(2));
        assertThat(resp).isNotNull();
        assertThat(resp.externalJobId()).isEqualTo("job-99");
    }

    @Test
    @DisplayName("VlmClient_로그_sanitize_개행_탭_문자_치환")
    void safeForLogReplacesControlChars() {
        // 정적 유틸 메서드 테스트 — 외부 응답 값 로그 출력 시 CRLF 제거
        assertThat(VlmClient.safeForLog("line1\nline2")).isEqualTo("line1_line2");
        assertThat(VlmClient.safeForLog("a\r\nb\tc")).isEqualTo("a__b_c");
        assertThat(VlmClient.safeForLog(null)).isEqualTo("null");
        assertThat(VlmClient.safeForLog("normal-id_123")).isEqualTo("normal-id_123");
    }
}
