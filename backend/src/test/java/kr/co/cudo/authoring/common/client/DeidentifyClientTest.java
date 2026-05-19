package kr.co.cudo.authoring.common.client;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import kr.co.cudo.authoring.common.client.dto.DeidentifyRequest;
import kr.co.cudo.authoring.common.client.dto.DeidentifyResponse;
import kr.co.cudo.authoring.webhook.idempotency.InMemoryWebhookIdempotencyLedger;
import kr.co.cudo.authoring.webhook.idempotency.LsWebhookIdempotency;
import kr.co.cudo.authoring.webhook.idempotency.WebhookIdempotencyLedger;
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
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 3 — DeidentifyClient idempotencyKey 발급 + webhook ledger 사전 기록 검증.
 */
class DeidentifyClientTest {

    private MockWebServer server;
    private CircuitBreaker circuitBreaker;
    private InMemoryWebhookIdempotencyLedger ledger;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        circuitBreaker = CircuitBreakerRegistry.of(
                CircuitBreakerConfig.custom()
                        .failureRateThreshold(50)
                        .slidingWindowSize(10)
                        .minimumNumberOfCalls(5)
                        .build())
                .circuitBreaker("deid");
        ledger = new InMemoryWebhookIdempotencyLedger();
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

    @Test
    @DisplayName("Phase3_위탁_시_idempotencyKey_헤더_포함됨_그리고_recordIssued_호출")
    void idempotencyHeaderAndLedgerRecord() throws InterruptedException {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"status\":\"OK\",\"resultPath\":\"/out/v.mp4\"}"));
        DeidentifyClient client = new DeidentifyClient(webClient(), circuitBreaker, singleAttempt(), ledger);

        DeidentifyResponse resp = client.deidentify(
                        new DeidentifyRequest("/in/v.mp4", "/out/v.mp4", "key-deid-1"))
                .block(Duration.ofSeconds(2));

        assertThat(resp).isNotNull();
        assertThat(resp.status()).isEqualTo("OK");
        RecordedRequest rec = server.takeRequest(2, TimeUnit.SECONDS);
        assertThat(rec).isNotNull();
        assertThat(rec.getHeader(DeidentifyClient.IDEMPOTENCY_HEADER)).isEqualTo("key-deid-1");
        // ledger 사전 기록 검증
        Optional<WebhookIdempotencyLedger.Entry> entry = ledger.lookup("key-deid-1");
        assertThat(entry).isPresent();
        assertThat(entry.get().state()).isEqualTo(WebhookIdempotencyLedger.State.ISSUED);
    }

    @Test
    @DisplayName("Phase3_idempotencyKey_null_제공_시_UUID_자동발급")
    void idempotencyKeyAutoIssuedWhenNull() throws InterruptedException {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"status\":\"OK\",\"resultPath\":\"/out/v.mp4\"}"));
        DeidentifyClient client = new DeidentifyClient(webClient(), circuitBreaker, singleAttempt(), ledger);

        client.deidentify(new DeidentifyRequest("/in/v.mp4", "/out/v.mp4", null))
                .block(Duration.ofSeconds(2));

        RecordedRequest rec = server.takeRequest(2, TimeUnit.SECONDS);
        assertThat(rec).isNotNull();
        String header = rec.getHeader(DeidentifyClient.IDEMPOTENCY_HEADER);
        assertThat(header).isNotNull().isNotBlank().matches("^[0-9a-fA-F-]{36}$");
        // ledger 도 같은 UUID 로 기록되어야 함
        assertThat(ledger.lookup(header)).isPresent();
    }

    @Test
    @DisplayName("Phase3_Retry_재시도_시_동일_idempotencyKey_재사용")
    void retryReusesSameIdempotencyKey() throws InterruptedException {
        // 첫 호출 500 → 두 번째 호출 성공
        server.enqueue(new MockResponse().setResponseCode(500));
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"status\":\"OK\",\"resultPath\":\"/out/v.mp4\"}"));
        DeidentifyClient client = new DeidentifyClient(webClient(), circuitBreaker, tripleAttemptShort(), ledger);

        client.deidentify(new DeidentifyRequest("/in/v.mp4", "/out/v.mp4", null))
                .block(Duration.ofSeconds(3));

        RecordedRequest r1 = server.takeRequest(2, TimeUnit.SECONDS);
        RecordedRequest r2 = server.takeRequest(2, TimeUnit.SECONDS);
        assertThat(r1).isNotNull();
        assertThat(r2).isNotNull();
        String k1 = r1.getHeader(DeidentifyClient.IDEMPOTENCY_HEADER);
        String k2 = r2.getHeader(DeidentifyClient.IDEMPOTENCY_HEADER);
        assertThat(k1).isNotBlank();
        assertThat(k2).isEqualTo(k1); // 재시도 시 동일 키 재사용
        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("Phase3_legacy_2arg_생성자_호환_유지")
    void legacyTwoArgConstructorStillWorks() {
        // ledger 미주입 — 기존 호환 생성자 사용 (NPE 없이 호출 가능해야 함)
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"status\":\"OK\",\"resultPath\":\"/out/v.mp4\"}"));
        DeidentifyClient client = new DeidentifyClient(webClient(),
                CircuitBreakerRegistry.ofDefaults().circuitBreaker("deid"),
                singleAttempt());

        DeidentifyResponse resp = client.deidentify(
                        new DeidentifyRequest("/in/v.mp4", "/out/v.mp4"))
                .block(Duration.ofSeconds(2));
        assertThat(resp).isNotNull();
        assertThat(resp.status()).isEqualTo("OK");
    }
}
