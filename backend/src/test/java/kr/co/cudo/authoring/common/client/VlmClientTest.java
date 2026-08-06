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
 * VlmClient 단위 테스트 — 벤더 확정 계약(v2.0.1) <b>verify</b> 규격 정합.
 *
 * <p>verify 요청({@code POST /v1/videovlm/verify}) 바디/엔드포인트 + 동기 응답
 * ({@code status="accepted"}, request_id echo) 수용을 검증한다. 동기 응답 형식은 구 describe 와
 * 동일하므로 검증 로직은 무변경이며, 바뀐 것은 <b>경로</b>와 <b>{@code event_type} 필드</b>다.
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
                        // V1: 4xx 비재시도 예외는 서킷 failure 로 집계하지 않는다(프로덕션 YAML 정합).
                        .ignoreExceptions(NonRetryableExternalException.class)
                        .build());
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    private WebClient webClient() {
        return WebClient.builder().baseUrl(server.url("/").toString()).build();
    }

    private RetryRegistry singleAttempt() {
        return RetryRegistry.of(RetryConfig.custom().maxAttempts(1).build());
    }

    private RetryRegistry tripleAttemptShort() {
        return RetryRegistry.of(RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ofMillis(10))
                .retryExceptions(RuntimeException.class)
                .build());
    }

    /** 프로덕션 정합 재시도 레지스트리 — 3회 재시도하되 4xx 비재시도 예외는 무시(V1). */
    private RetryRegistry tripleAttemptIgnoringNonRetryable() {
        return RetryRegistry.of(RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ofMillis(10))
                .ignoreExceptions(NonRetryableExternalException.class)
                .build());
    }

    private VlmTimeseriesRequest verifyReq(String requestId) {
        return VlmTimeseriesRequest.ofFrameInterval(
                requestId, "fall", "/data/videos/deid.mp4", 25,
                "http://localhost:8080/api/v1/vlm/callback");
    }

    @Test
    @DisplayName("위탁_요청_endpoint가_videovlm_verify_이고_바디에_request_id_event_type_media_frame_policy_callback_url_포함")
    void verifyEndpointAndBody() throws InterruptedException {
        // given — accepted 응답 stub
        retryRegistry = singleAttempt();
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"request_id\":\"req-abc\",\"status\":\"accepted\"}"));
        VlmClient client = new VlmClient(webClient(), cbRegistry, retryRegistry, true, 5L);

        // when
        VlmTimeseriesResponse resp = client.submitTimeseries(verifyReq("req-abc"))
                .block(Duration.ofSeconds(2));

        // then — endpoint + verify 바디 필드 확인
        assertThat(resp).isNotNull();
        assertThat(resp.status()).isEqualTo("accepted");
        RecordedRequest recorded = server.takeRequest(2, TimeUnit.SECONDS);
        assertThat(recorded).isNotNull();
        assertThat(recorded.getPath()).isEqualTo(VlmClient.VERIFY_PATH);
        assertThat(VlmClient.VERIFY_PATH).isEqualTo("/v1/videovlm/verify");
        String body = recorded.getBody().readUtf8();
        assertThat(body).contains("\"request_id\":\"req-abc\"");
        assertThat(body).contains("\"event_type\":\"fall\"");
        assertThat(body).contains("\"media\"");
        assertThat(body).contains("\"source_type\":\"path\"");
        assertThat(body).contains("\"path\":\"/data/videos/deid.mp4\"");
        assertThat(body).contains("\"frame_policy\"");
        assertThat(body).contains("\"mode\":\"frame_interval\"");
        assertThat(body).contains("\"framerate\":25");
        assertThat(body).contains("\"callback_url\":\"http://localhost:8080/api/v1/vlm/callback\"");
        // 벤더 규격 밖 필드 미전송 — 마킹 원문은 frame_policy 로만 반영된다
        assertThat(body).doesNotContain("eventName");
        assertThat(body).doesNotContain("marks");
    }

    @Test
    @DisplayName("동기응답_status_accepted_수용하고_request_id_echo_확인")
    void acceptedStatusAndRequestIdEcho() {
        retryRegistry = singleAttempt();
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"request_id\":\"req-echo\",\"status\":\"accepted\"}"));
        VlmClient client = new VlmClient(webClient(), cbRegistry, retryRegistry, true, 5L);

        VlmTimeseriesResponse resp = client.submitTimeseries(verifyReq("req-echo"))
                .block(Duration.ofSeconds(2));

        assertThat(resp).isNotNull();
        assertThat(resp.requestId()).isEqualTo("req-echo");
        assertThat(resp.status()).isEqualTo("accepted");
    }

    @Test
    @DisplayName("응답_request_id_echo_불일치_시_EXTERNAL_API_ERROR")
    void requestIdEchoMismatchRejected() {
        retryRegistry = singleAttempt();
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"request_id\":\"OTHER\",\"status\":\"accepted\"}"));
        VlmClient client = new VlmClient(webClient(), cbRegistry, retryRegistry, true, 5L);

        assertThatThrownBy(() -> client.submitTimeseries(verifyReq("req-abc"))
                .block(Duration.ofSeconds(2)))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("request_id");
    }

    @Test
    @DisplayName("응답_status_accepted_아니면_EXTERNAL_API_ERROR")
    void nonAcceptedStatusRejected() {
        retryRegistry = singleAttempt();
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"request_id\":\"req-abc\",\"status\":\"rejected\"}"));
        VlmClient client = new VlmClient(webClient(), cbRegistry, retryRegistry, true, 5L);

        assertThatThrownBy(() -> client.submitTimeseries(verifyReq("req-abc"))
                .block(Duration.ofSeconds(2)))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("accepted");
    }

    @Test
    @DisplayName("enabled_false_시_외부_호출_없이_SKIPPED_반환_NO_OP")
    void enabledFalseReturnsSkippedWithoutCall() {
        retryRegistry = singleAttempt();
        VlmClient client = new VlmClient(webClient(), cbRegistry, retryRegistry, false, 5L);

        VlmTimeseriesResponse resp = client.submitTimeseries(verifyReq("req-x"))
                .block(Duration.ofSeconds(2));

        assertThat(resp).isNotNull();
        assertThat(resp.status()).isEqualTo("skipped");
        assertThat(resp.requestId()).isEqualTo("req-x");
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    @DisplayName("호출자가_request_id_null_제공_시_UUID_자동발급_방어")
    void requestIdAutoIssuedWhenNull() throws InterruptedException {
        retryRegistry = singleAttempt();
        // 응답은 서버가 echo 를 모르므로, 클라이언트가 발급한 request_id 를 알 수 없다.
        // enabled=false 경로로 자동발급만 검증한다(외부 호출 없이 UUID 발급).
        VlmClient disabled = new VlmClient(webClient(), cbRegistry, retryRegistry, false, 5L);

        VlmTimeseriesResponse resp = disabled.submitTimeseries(
                VlmTimeseriesRequest.ofFrameInterval(null, "fire", "/data/deid.mp4", 25, "http://cb"))
                .block(Duration.ofSeconds(2));

        assertThat(resp).isNotNull();
        assertThat(resp.requestId()).isNotBlank();
        assertThat(resp.requestId()).matches("^[0-9a-fA-F-]{36}$");
    }

    @Test
    @DisplayName("타임아웃_500_시_재시도_3회_후_예외_전파")
    void retryThreeTimesOnFailure() {
        retryRegistry = tripleAttemptShort();
        for (int i = 0; i < 3; i++) {
            server.enqueue(new MockResponse().setResponseCode(500));
        }
        VlmClient client = new VlmClient(webClient(), cbRegistry, retryRegistry, true, 5L);

        assertThatThrownBy(() -> client.submitTimeseries(verifyReq("req-fail"))
                .block(Duration.ofSeconds(5)))
                .isInstanceOf(RuntimeException.class);

        assertThat(server.getRequestCount()).isEqualTo(3);
    }

    // ===== V1: 4xx(400/422) 비재시도 분류 — 벤더 규격(§4.1) 비-일시적 오류 =====

    @Test
    @DisplayName("V1_400_형식오류는_재시도없이_1회요청_비재시도예외전파")
    void badRequest400NotRetried() {
        // given — 벤더 규격상 400(형식오류)은 비-일시적. 3회 재시도 설정이라도 재시도되지 않아야 한다.
        retryRegistry = tripleAttemptIgnoringNonRetryable();
        server.enqueue(new MockResponse().setResponseCode(400));
        VlmClient client = new VlmClient(webClient(), cbRegistry, retryRegistry, true, 5L);

        // when / then
        assertThatThrownBy(() -> client.submitTimeseries(verifyReq("req-400"))
                .block(Duration.ofSeconds(2)))
                .isInstanceOf(NonRetryableExternalException.class);
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("V1_422_파라미터값오류도_재시도없이_1회요청")
    void unprocessable422NotRetried() {
        // given — 422(파라미터 값 오류)도 비-일시적.
        retryRegistry = tripleAttemptIgnoringNonRetryable();
        server.enqueue(new MockResponse().setResponseCode(422));
        VlmClient client = new VlmClient(webClient(), cbRegistry, retryRegistry, true, 5L);

        assertThatThrownBy(() -> client.submitTimeseries(verifyReq("req-422"))
                .block(Duration.ofSeconds(2)))
                .isInstanceOf(NonRetryableExternalException.class);
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("응답_unknown_필드_있어도_역직렬화_통과")
    void responseUnknownFieldsIgnored() {
        retryRegistry = singleAttempt();
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"request_id\":\"req-u\",\"status\":\"accepted\",\"extraEvil\":\"<script>\"}"));
        VlmClient client = new VlmClient(webClient(), cbRegistry, retryRegistry, true, 5L);

        VlmTimeseriesResponse resp = client.submitTimeseries(verifyReq("req-u"))
                .block(Duration.ofSeconds(2));
        assertThat(resp).isNotNull();
        assertThat(resp.requestId()).isEqualTo("req-u");
    }

    @Test
    @DisplayName("isEnabled_토글_상태_노출")
    void isEnabledExposesToggle() {
        retryRegistry = singleAttempt();
        VlmClient disabled = new VlmClient(webClient(), cbRegistry, retryRegistry, false, 5L);
        VlmClient enabled = new VlmClient(webClient(), cbRegistry, retryRegistry, true, 5L);
        assertThat(disabled.isEnabled()).isFalse();
        assertThat(enabled.isEnabled()).isTrue();
    }

    @Test
    @DisplayName("로그_sanitize_개행_탭_문자_치환")
    void safeForLogReplacesControlChars() {
        assertThat(VlmClient.safeForLog("line1\nline2")).isEqualTo("line1_line2");
        assertThat(VlmClient.safeForLog("a\r\nb\tc")).isEqualTo("a__b_c");
        assertThat(VlmClient.safeForLog(null)).isEqualTo("null");
        assertThat(VlmClient.safeForLog("normal-id_123")).isEqualTo("normal-id_123");
    }
}
