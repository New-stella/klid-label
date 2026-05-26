package kr.co.cudo.authoring.observability.health;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 5 -- ControlNotifyHealthIndicator 단위 테스트.
 */
class ControlNotifyHealthIndicatorTest {

    private MockWebServer mockServer;
    private ControlNotifyHealthIndicator indicator;

    @BeforeEach
    void setUp() throws IOException {
        mockServer = new MockWebServer();
        mockServer.start();
        WebClient webClient = WebClient.builder()
                .baseUrl(mockServer.url("/").toString())
                .build();
        indicator = new ControlNotifyHealthIndicator(webClient);
    }

    @AfterEach
    void tearDown() throws IOException {
        mockServer.shutdown();
    }

    @Test
    @DisplayName("관제서버_정상응답시_UP")
    void health_up_when_server_responds_ok() {
        // given
        mockServer.enqueue(new MockResponse().setResponseCode(200));

        // when
        Health health = indicator.health();

        // then
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("service", "control-notify");
    }

    @Test
    @DisplayName("관제서버_타임아웃시_DOWN")
    void health_down_when_server_times_out() {
        // given -- 응답 자체를 지연하여 PING_TIMEOUT(2초) 초과 유도
        mockServer.enqueue(new MockResponse()
                .setSocketPolicy(SocketPolicy.NO_RESPONSE));

        // when
        Health health = indicator.health();

        // then
        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("service", "control-notify");
        assertThat(health.getDetails()).containsKey("error");
        // CWE-209: 에러 상세에 스택트레이스/내부 정보 미포함
        assertThat(health.getDetails().get("error").toString()).doesNotContain("\n");
    }

    @Test
    @DisplayName("관제서버_500_응답시_DOWN")
    void health_down_when_server_returns_500() {
        // given
        mockServer.enqueue(new MockResponse().setResponseCode(500));

        // when
        Health health = indicator.health();

        // then
        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("service", "control-notify");
        assertThat(health.getDetails()).containsKey("error");
    }
}
