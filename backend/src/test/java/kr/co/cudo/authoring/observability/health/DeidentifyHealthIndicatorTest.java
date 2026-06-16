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
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DeidentifyHealthIndicator 단위 테스트 (Phase 12 + 로컬 외부0개 mock 모드).
 * <p>
 * 프로파일/YAML 의존 없이 인디케이터 로직만 검증한다 — mock-mode 플래그는
 * {@link ReflectionTestUtils} 로 직접 주입한다.
 */
class DeidentifyHealthIndicatorTest {

    private MockWebServer mockServer;
    private WebClient webClient;
    private DeidentifyHealthIndicator indicator;

    @BeforeEach
    void setUp() throws IOException {
        mockServer = new MockWebServer();
        mockServer.start();
        webClient = WebClient.builder()
                .baseUrl(mockServer.url("/").toString())
                .build();
        indicator = new DeidentifyHealthIndicator(webClient);
    }

    @AfterEach
    void tearDown() throws IOException {
        mockServer.shutdown();
    }

    @Test
    @DisplayName("mock모드_활성시_외부핑없이_UP_mock")
    void health_up_mock_when_mock_mode_enabled() {
        // given — local 외부0개: 실 비식별 서버 부재. 핑하지 않아야 한다.
        ReflectionTestUtils.setField(indicator, "mockMode", true);

        // when
        Health health = indicator.health();

        // then — 서버에 요청이 전혀 가지 않고 UP(mock).
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("service", "deidentify");
        assertThat(health.getDetails()).containsEntry("mode", "mock");
        assertThat(mockServer.getRequestCount()).isZero();
    }

    @Test
    @DisplayName("실모드_서버정상응답시_UP")
    void health_up_when_server_responds_ok() {
        // given — 운영(mock-mode=false 기본): 실 서버 핑.
        mockServer.enqueue(new MockResponse().setResponseCode(200));

        // when
        Health health = indicator.health();

        // then
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("service", "deidentify");
    }

    @Test
    @DisplayName("실모드_서버타임아웃시_DOWN")
    void health_down_when_server_times_out() {
        // given — PING_TIMEOUT(2초) 초과 유도
        mockServer.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));

        // when
        Health health = indicator.health();

        // then
        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("service", "deidentify");
        assertThat(health.getDetails()).containsKey("error");
        // CWE-209: 에러 상세에 스택트레이스/내부 정보 미포함
        assertThat(health.getDetails().get("error").toString()).doesNotContain("\n");
    }
}
