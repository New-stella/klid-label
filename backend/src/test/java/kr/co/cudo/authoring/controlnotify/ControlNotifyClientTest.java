package kr.co.cudo.authoring.controlnotify;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import kr.co.cudo.authoring.common.client.ControlNotifyClient;
import kr.co.cudo.authoring.controlnotify.dto.TaskCompletedPayload;
import kr.co.cudo.authoring.controlnotify.dto.TaskModifiedPayload;
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
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 2 (통지 인프라) — ControlNotifyClient 단위 테스트.
 *
 * <p>MockWebServer 를 사용하여 관제서버 통지 API 호출을 검증한다.
 */
class ControlNotifyClientTest {

    private MockWebServer mockServer;
    private ControlNotifyClient client;
    private CircuitBreaker circuitBreaker;

    @BeforeEach
    void setUp() throws IOException {
        mockServer = new MockWebServer();
        mockServer.start();

        String baseUrl = mockServer.url("/").toString();
        WebClient webClient = WebClient.builder().baseUrl(baseUrl).build();

        CircuitBreakerConfig cbConfig = CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .slidingWindowSize(10)
                .minimumNumberOfCalls(5)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .build();
        circuitBreaker = CircuitBreakerRegistry.of(cbConfig).circuitBreaker("controlNotify");

        RetryConfig retryConfig = RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ofMillis(100))
                .build();
        RetryRegistry retryRegistry = RetryRegistry.of(retryConfig);

        client = new ControlNotifyClient(webClient, circuitBreaker, retryRegistry);
    }

    @AfterEach
    void tearDown() throws IOException {
        mockServer.shutdown();
    }

    @Test
    @DisplayName("TASK_COMPLETED_통지_정상_송신시_200")
    void sendTaskCompletedSuccess() throws InterruptedException {
        // given
        mockServer.enqueue(new MockResponse().setResponseCode(200));
        TaskCompletedPayload payload = new TaskCompletedPayload(
                "TASK_COMPLETED", 100L, "reviewer1",
                Instant.parse("2026-05-26T09:00:00Z"),
                30, 25, "req-001");

        // when
        client.sendTaskCompleted(payload)
                .block(ControlNotifyClient.BLOCK_TIMEOUT);

        // then
        RecordedRequest request = mockServer.takeRequest(5, TimeUnit.SECONDS);
        assertThat(request).isNotNull();
        assertThat(request.getMethod()).isEqualTo("POST");
        String body = request.getBody().readUtf8();
        assertThat(body).contains("TASK_COMPLETED");
        assertThat(body).contains("100");
    }

    @Test
    @DisplayName("TASK_MODIFIED_통지_정상_송신시_200")
    void sendTaskModifiedSuccess() throws InterruptedException {
        // given
        mockServer.enqueue(new MockResponse().setResponseCode(200));
        TaskModifiedPayload payload = new TaskModifiedPayload(
                "TASK_MODIFIED", 200L,
                Instant.parse("2026-05-26T10:00:00Z"),
                List.of(1L, 2L),
                List.of("LABEL_ADDED", "LABEL_UPDATED"),
                "req-002");

        // when
        client.sendTaskModified(payload)
                .block(ControlNotifyClient.BLOCK_TIMEOUT);

        // then
        RecordedRequest request = mockServer.takeRequest(5, TimeUnit.SECONDS);
        assertThat(request).isNotNull();
        assertThat(request.getMethod()).isEqualTo("POST");
        String body = request.getBody().readUtf8();
        assertThat(body).contains("TASK_MODIFIED");
        assertThat(body).contains("200");
    }

    @Test
    @DisplayName("관제서버_500_응답시_Retry_3회_후_실패")
    void retryOnServerError() {
        // given -- 3회 모두 500 응답
        mockServer.enqueue(new MockResponse().setResponseCode(500));
        mockServer.enqueue(new MockResponse().setResponseCode(500));
        mockServer.enqueue(new MockResponse().setResponseCode(500));

        TaskCompletedPayload payload = new TaskCompletedPayload(
                "TASK_COMPLETED", 100L, "reviewer1",
                Instant.now(), 30, 25, "req-retry");

        // when / then
        assertThatThrownBy(() ->
                client.sendTaskCompleted(payload)
                        .block(ControlNotifyClient.BLOCK_TIMEOUT))
                .isNotNull();

        // 3회 요청 확인
        assertThat(mockServer.getRequestCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("관제서버_타임아웃시_CircuitBreaker_동작")
    void circuitBreakerOpensOnFailures() {
        // given -- CircuitBreaker를 OPEN 상태로 강제 전이
        circuitBreaker.transitionToOpenState();

        TaskCompletedPayload payload = new TaskCompletedPayload(
                "TASK_COMPLETED", 100L, "reviewer1",
                Instant.now(), 30, 25, "req-cb");

        // when / then -- OPEN 상태에서는 CallNotPermittedException 발생
        assertThatThrownBy(() ->
                client.sendTaskCompleted(payload)
                        .block(ControlNotifyClient.BLOCK_TIMEOUT))
                .isNotNull();

        // 요청이 서버에 도달하지 않아야 함
        assertThat(mockServer.getRequestCount()).isZero();
    }
}
