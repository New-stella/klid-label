package kr.co.cudo.authoring.common.client;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import kr.co.cudo.authoring.common.client.dto.KpstReportResponse;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * R14 — {@code GET /retrieve_report} 클라이언트 검증.
 *
 * <p>진행조회와 마찬가지로 <b>GET 요청에 JSON 바디</b>를 실어 보내야 하므로, 저수준 reactor-netty
 * {@code HttpClient} 경로를 그대로 쓴다. MockWebServer 4.12 는 {@code GET + body} 를 서버 측에서
 * 거부하므로(하네스 한계) JDK 내장 {@code HttpServer} 로 모킹한다 —
 * {@code KpstDeidentifyClientTest} 의 진행조회 모킹과 동일한 이유·동일한 방식이다.
 */
class KpstDeidentifyClientReportTest {

    private MockWebServer server;
    private com.sun.net.httpserver.HttpServer reportServer;
    private CircuitBreaker circuitBreaker;

    private volatile String requestMethod;
    private volatile String requestPath;
    private volatile String requestBody;
    private final AtomicInteger requestCount = new AtomicInteger();

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        circuitBreaker = CircuitBreakerRegistry.of(
                        CircuitBreakerConfig.custom()
                                .failureRateThreshold(50)
                                .slidingWindowSize(10)
                                .minimumNumberOfCalls(5)
                                .ignoreExceptions(NonRetryableExternalException.class)
                                .build())
                .circuitBreaker("kpstDeid");
        reportServer = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        reportServer.start();
        requestCount.set(0);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
        if (reportServer != null) {
            reportServer.stop(0);
        }
    }

    private KpstDeidentifyClient client() {
        return new KpstDeidentifyClient(
                WebClient.builder().baseUrl(server.url("/").toString()).build(),
                HttpClient.create()
                        .baseUrl("http://" + reportServer.getAddress().getHostString()
                                + ":" + reportServer.getAddress().getPort())
                        .responseTimeout(Duration.ofSeconds(5)),
                circuitBreaker,
                RetryRegistry.of(RetryConfig.custom().maxAttempts(1).build()));
    }

    private void installJsonDispatcher(String jsonBody) {
        byte[] respBytes = jsonBody.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        reportServer.createContext("/retrieve_report", exchange -> {
            try (exchange) {
                requestCount.incrementAndGet();
                requestMethod = exchange.getRequestMethod();
                requestPath = exchange.getRequestURI().getPath();
                requestBody = new String(exchange.getRequestBody().readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, respBytes.length);
                exchange.getResponseBody().write(respBytes);
            }
        });
    }

    private void installStatusDispatcher(int status) {
        reportServer.createContext("/retrieve_report", exchange -> {
            try (exchange) {
                requestCount.incrementAndGet();
                exchange.getRequestBody().readAllBytes();
                exchange.sendResponseHeaders(status, -1);
            }
        });
    }

    @Test
    @DisplayName("리포트조회는_GET에_JSON바디로_필터를_실어_보내고_집계값을_돌려준다")
    void retrieveReportSendsJsonBodyOnGet() {
        installJsonDispatcher("""
                {"result":"success","data":{"prjCount":1,"prjStatus":[
                  {"progressRate":100.0,"dsStatus":[
                    {"dsId":1270,"fileName":"/nas/raw/001.mp4","faceCount":12,"lpCount":3,
                     "totalFrame":5400,"startTime":"2026-08-11 10:00:00",
                     "endTime":"2026-08-11 10:05:30"}]}]}}
                """);

        KpstReportResponse res = client().retrieveReport("authoring", 279L);

        assertThat(requestMethod).isEqualTo("GET");
        assertThat(requestPath).isEqualTo("/retrieve_report");
        assertThat(requestBody).contains("\"reqUserId\":\"authoring\"").contains("\"prjId\":279");
        KpstReportResponse.DsStatus ds = res.data().prjStatus().get(0).dsStatus().get(0);
        assertThat(ds.dsId()).isEqualTo(1270L);
        assertThat(ds.faceCount()).isEqualTo(12L);
        assertThat(ds.lpCount()).isEqualTo(3L);
    }

    @Test
    @DisplayName("리포트조회_오류응답은_외부본문을_노출하지_않는_표준예외로_변환된다")
    void retrieveReportMapsErrorStatus() {
        installStatusDispatcher(500);

        assertThatThrownBy(() -> client().retrieveReport("authoring", 279L))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                        .isEqualTo(ErrorCode.EXTERNAL_API_ERROR));
    }

    @Test
    @DisplayName("리포트조회_4xx는_재시도하지_않는다")
    void retrieveReport4xxIsNotRetried() {
        installStatusDispatcher(400);
        KpstDeidentifyClient client = new KpstDeidentifyClient(
                WebClient.builder().baseUrl(server.url("/").toString()).build(),
                HttpClient.create()
                        .baseUrl("http://" + reportServer.getAddress().getHostString()
                                + ":" + reportServer.getAddress().getPort())
                        .responseTimeout(Duration.ofSeconds(5)),
                circuitBreaker,
                RetryRegistry.of(RetryConfig.custom()
                        .maxAttempts(3)
                        .waitDuration(Duration.ofMillis(10))
                        .ignoreExceptions(NonRetryableExternalException.class)
                        .build()));

        assertThatThrownBy(() -> client.retrieveReport("authoring", 279L))
                .isInstanceOf(CustomException.class);

        assertThat(requestCount.get()).isEqualTo(1);
    }
}
