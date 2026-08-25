package kr.co.cudo.authoring.observability.health;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import kr.co.cudo.authoring.common.client.VlmClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * VlmHealthIndicator 단위 테스트.
 *
 * <p>핵심 축은 <b>"응답이 오면 UP, 연결 자체가 안 되면 DOWN"</b> 이다. 특히
 * {@link #health_up_when_server_responds_4xx()} 는 관제 헬스 사고(존재하지 않는 경로 → 403 →
 * 집계 헬스 전체 상시 DOWN)의 재발 방지 가드다.
 *
 * <p>프로파일/YAML 의존 없이 실행 경로와 같은 {@link VlmClient} 를 목 서버에 붙여 검증한다.
 * 상태 조회는 재시도·서킷을 태우지 않으므로 레지스트리는 기본값으로 충분하다.
 */
class VlmHealthIndicatorTest {

    /** 연동 규격(KLID 연동 API v1.1.0 §3.5)이 정의한 서버 상태 조회 경로. */
    private static final String STATUS_PATH = "/v1/videovlm-klid/status";

    /** 클라이언트 자체 타임아웃(운영 기본값). 인디케이터의 2초 상한이 이보다 먼저 만료돼야 한다. */
    private static final long CLIENT_TIMEOUT_SECONDS = 10;

    private MockWebServer mockServer;
    private VlmHealthIndicator indicator;

    @BeforeEach
    void setUp() throws IOException {
        mockServer = new MockWebServer();
        mockServer.start();
        WebClient webClient = WebClient.builder()
                .baseUrl(mockServer.url("/").toString())
                .build();
        VlmClient vlmClient = new VlmClient(
                webClient,
                CircuitBreakerRegistry.ofDefaults(),
                RetryRegistry.ofDefaults(),
                CLIENT_TIMEOUT_SECONDS);
        indicator = new VlmHealthIndicator(vlmClient);
    }

    @AfterEach
    void tearDown() throws IOException {
        mockServer.shutdown();
    }

    @Test
    @DisplayName("상태_ready면_UP")
    void health_up_when_status_ready() throws InterruptedException {
        // given
        enqueueStatus("{\"status\":\"ready\",\"queue\":0,\"pending\":0}");

        // when
        Health health = indicator.health();

        // then
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .containsEntry("service", "vlm")
                .containsEntry("status", "ready")
                .containsEntry("queue", 0)
                .containsEntry("pending", 0);

        // 규격이 보장하는 상태 창구를 친다 — 임의 경로(/health)를 만들지 않는다.
        RecordedRequest recorded = mockServer.takeRequest();
        assertThat(recorded.getPath()).isEqualTo(STATUS_PATH);
        assertThat(recorded.getMethod()).isEqualTo("GET");
    }

    @Test
    @DisplayName("상태_busy여도_UP")
    void health_up_when_status_busy() {
        // given — busy 는 "접수는 되고 결과만 지연"이라 서버는 살아 있다.
        enqueueStatus("{\"status\":\"busy\",\"queue\":7,\"pending\":3}");

        // when
        Health health = indicator.health();

        // then
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .containsEntry("service", "vlm")
                .containsEntry("status", "busy")
                .containsEntry("queue", 7)
                .containsEntry("pending", 3);
    }

    @Test
    @DisplayName("상태_loading여도_UP")
    void health_up_when_status_loading() {
        // given — loading 은 위탁을 미룰 사유이지 서버가 죽었다는 뜻이 아니다.
        enqueueStatus("{\"status\":\"loading\",\"queue\":0,\"pending\":0}");

        // when
        Health health = indicator.health();

        // then
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("status", "loading");
    }

    @Test
    @DisplayName("4xx_응답이어도_UP")
    void health_up_when_server_responds_4xx() {
        // given — 관제 사고 재현: 응답이 왔는데 상태코드가 4xx.
        //   응답이 왔다는 것 자체가 서버 생존 증거이므로 DOWN 으로 내리지 않는다.
        mockServer.enqueue(new MockResponse().setResponseCode(403));

        // when
        Health health = indicator.health();

        // then
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .containsEntry("service", "vlm")
                .containsEntry("httpStatus", 403);
        // CWE-209: 응답 본문·주소를 싣지 않는다.
        assertThat(health.getDetails()).doesNotContainKey("error");
    }

    @Test
    @DisplayName("5xx_응답이어도_UP")
    void health_up_when_server_responds_5xx() {
        // given
        mockServer.enqueue(new MockResponse().setResponseCode(503));

        // when
        Health health = indicator.health();

        // then
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("httpStatus", 503);
    }

    @Test
    @DisplayName("응답은_200인데_본문이_JSON이_아니어도_UP")
    void health_up_when_body_is_not_json() {
        // given — 리버스 프록시·LB 가 200 과 함께 HTML 오류 페이지를 돌려주는 형상.
        //   Content-Type 은 JSON 이라 디코더는 붙지만 본문 파싱이 깨진다(DecodingException).
        //   응답이 도달한 이상 서버는 살아 있으므로 DOWN 이 아니다 — 여기서 DOWN 을 내면 관제 사고와
        //   똑같이 집계 /actuator/health 가 상시 DOWN 이 된다.
        mockServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .setBody("<html><body>502 Bad Gateway</body></html>"));

        // when
        Health health = indicator.health();

        // then
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .containsEntry("service", "vlm")
                .containsEntry("decoded", false);
        // CWE-209: 응답 본문·예외 메시지를 싣지 않는다(디코딩 예외 메시지에는 본문 조각이 섞인다).
        assertThat(health.getDetails()).doesNotContainKey("error");
        assertThat(health.getDetails().values())
                .noneMatch(v -> String.valueOf(v).contains("Bad Gateway"));
    }

    @Test
    @DisplayName("ContentType이_text_html이어도_UP")
    void health_up_when_content_type_is_html() {
        // given — 해석할 디코더가 아예 없는 경우. 본문 추출이 UnsupportedMediaTypeException 을
        //   WebClientResponseException(status=200) 으로 감싸 던진다(실측).
        mockServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_HTML_VALUE)
                .setBody("<html><body>502 Bad Gateway</body></html>"));

        // when
        Health health = indicator.health();

        // then
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .containsEntry("service", "vlm")
                .containsEntry("httpStatus", 200)
                .containsEntry("decoded", false);
        assertThat(health.getDetails()).doesNotContainKey("error");
    }

    @Test
    @DisplayName("연결_불가면_DOWN")
    void health_down_when_connection_fails() {
        // given — 응답 자체가 오지 않는다(인디케이터 상한 2초 초과 유도).
        mockServer.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));

        // when
        long startedAt = System.nanoTime();
        Health health = indicator.health();
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;

        // then
        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("service", "vlm");
        assertThat(health.getDetails()).containsKey("error");
        // CWE-209: 예외 클래스명만 — 스택트레이스/주소 미노출.
        assertThat(health.getDetails().get("error").toString()).doesNotContain("\n");
        // 클라이언트 자체 타임아웃(10초)이 아니라 인디케이터 상한(2초)이 먼저 만료돼야 한다.
        //   상한 2초 + 여유 1초로 조인다 — 10초 단언은 클라이언트 타임아웃이 그대로 새어도 통과한다.
        assertThat(elapsedMillis).isLessThan(3_000);
        assertThat(elapsedMillis).isLessThan(CLIENT_TIMEOUT_SECONDS * 1000);
    }

    @Test
    @DisplayName("서버가_꺼져있으면_DOWN")
    void health_down_when_server_is_shut_down() throws IOException {
        // given — 커넥션 거부(연결 자체가 성립하지 않음).
        mockServer.shutdown();

        // when
        Health health = indicator.health();

        // then
        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails())
                .containsEntry("service", "vlm")
                .containsKey("error");
    }

    private void enqueueStatus(String jsonBody) {
        mockServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .setBody(jsonBody));
    }
}
