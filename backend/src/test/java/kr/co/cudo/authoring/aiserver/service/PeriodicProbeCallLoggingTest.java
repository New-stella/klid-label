package kr.co.cudo.authoring.aiserver.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import kr.co.cudo.authoring.aiserver.entity.LsAiSrvr;
import kr.co.cudo.authoring.common.client.ExternalCallLoggingFilter;
import kr.co.cudo.authoring.common.client.VlmClient;
import kr.co.cudo.authoring.common.config.ExternalEndpointAddress;
import kr.co.cudo.authoring.common.config.KpstWebClientConfig;
import kr.co.cudo.authoring.common.config.VlmUrlPolicy;
import kr.co.cudo.authoring.common.config.WebClientConfig;
import kr.co.cudo.authoring.observability.health.DeidentifyHealthIndicator;
import kr.co.cudo.authoring.observability.health.VlmHealthIndicator;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 주기 점검 호출은 <b>성공했을 때만</b> 호출 로그를 DEBUG 로 낮춘다 — 실제 점검 경로 그대로 (NFR-038 v3).
 *
 * <p>점검기 → 실제 클라이언트 → 로그 필터가 붙은 실제 빈 → 실제 소켓. 호출부가 표식을 빠뜨리면
 * 성공 로그가 INFO 로 찍혀 이 시험이 실패한다. 위탁 직전 상태 조회({@code fetchStatus()})는 위탁 흐름의
 * 일부라 INFO 로 남는다는 것도 같이 고정한다.
 */
class PeriodicProbeCallLoggingTest {

    private MockWebServer server;
    private Logger filterLogger;
    private Level originalLevel;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        filterLogger = (Logger) LoggerFactory.getLogger(ExternalCallLoggingFilter.class);
        originalLevel = filterLogger.getLevel();
        filterLogger.setLevel(Level.DEBUG);
        appender = new ListAppender<>();
        appender.start();
        filterLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() throws IOException {
        filterLogger.detachAppender(appender);
        filterLogger.setLevel(originalLevel);
        server.shutdown();
    }

    private String base() {
        return server.url("/").toString();
    }

    private VlmClient vlmClient() {
        WebClient webClient = new WebClientConfig().vlmWebClient(base(), "", new VlmUrlPolicy(), null);
        return new VlmClient(webClient, CircuitBreakerRegistry.ofDefaults(),
                RetryRegistry.of(RetryConfig.custom().maxAttempts(1).build()), 5L);
    }

    private List<ILoggingEvent> completed() {
        return appender.list.stream()
                .filter(e -> e.getFormattedMessage().startsWith("[ExternalCall] completed"))
                .collect(Collectors.toList());
    }

    private void enqueueStatus(int code) {
        server.enqueue(new MockResponse().setResponseCode(code)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"status\":\"ok\"}"));
    }

    @Test
    @DisplayName("★시계열_장비_상태점검의_성공은_DEBUG로만_남는다")
    void 시계열_상태점검_성공은_DEBUG() {
        enqueueStatus(200);
        HttpAiSrvrHealthProbe probe = new HttpAiSrvrHealthProbe(WebClient.builder(), vlmClient());
        LsAiSrvr node = LsAiSrvr.register("vendor-1", null, base(), LsAiSrvr.SrvrType.TIMESERIES,
                LocalDateTime.now());

        assertThat(probe.ping(node)).isTrue();

        assertThat(completed()).singleElement().satisfies(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.DEBUG);
            assertThat(e.getFormattedMessage()).contains("integration=VLM method=GET")
                    .contains("/v1/videovlm-klid/status status=200");
        });
    }

    @Test
    @DisplayName("시계열_장비_상태점검의_500은_WARN으로_남는다")
    void 시계열_상태점검_실패는_WARN() {
        enqueueStatus(500);
        HttpAiSrvrHealthProbe probe = new HttpAiSrvrHealthProbe(WebClient.builder(), vlmClient());
        LsAiSrvr node = LsAiSrvr.register("vendor-1", null, base(), LsAiSrvr.SrvrType.TIMESERIES,
                LocalDateTime.now());

        probe.ping(node);

        assertThat(completed()).singleElement()
                .satisfies(e -> assertThat(e.getLevel()).isEqualTo(Level.WARN));
    }

    @Test
    @DisplayName("시계열_헬스_인디케이터의_성공도_DEBUG다")
    void 시계열_헬스_성공은_DEBUG() {
        enqueueStatus(200);

        new VlmHealthIndicator(vlmClient(), null).health();

        assertThat(completed()).singleElement()
                .satisfies(e -> assertThat(e.getLevel()).isEqualTo(Level.DEBUG));
    }

    @Test
    @DisplayName("위탁_직전_상태조회는_위탁_흐름의_일부라_INFO로_남는다")
    void 위탁직전_상태조회는_INFO() {
        enqueueStatus(200);

        vlmClient().fetchStatus().block(Duration.ofSeconds(5));
        enqueueStatus(200);
        vlmClient().fetchStatus(base()).block(Duration.ofSeconds(5));

        assertThat(completed()).hasSize(2)
                .allSatisfy(e -> assertThat(e.getLevel()).isEqualTo(Level.INFO));
    }

    @Test
    @DisplayName("비식별_헬스_핑의_성공은_DEBUG다")
    void 비식별_헬스_성공은_DEBUG() {
        server.enqueue(new MockResponse().setResponseCode(200));
        KpstWebClientConfig kpst = new KpstWebClientConfig();
        ExternalEndpointAddress address = kpst.kpstDeidEndpointAddress(base(), "", null);
        DeidentifyHealthIndicator indicator =
                new DeidentifyHealthIndicator(kpst.kpstDeidWebClient(address, "", null), null);
        ReflectionTestUtils.setField(indicator, "kpstEnabled", true);

        indicator.health();

        assertThat(completed()).singleElement().satisfies(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.DEBUG);
            assertThat(e.getFormattedMessage()).contains("integration=DEIDENTIFY");
        });
    }
}
