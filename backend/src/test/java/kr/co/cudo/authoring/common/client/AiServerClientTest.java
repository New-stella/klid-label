package kr.co.cudo.authoring.common.client;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import kr.co.cudo.authoring.common.client.dto.Sam2TrackRequest;
import kr.co.cudo.authoring.common.client.dto.Sam2TrackResponse;
import kr.co.cudo.authoring.common.client.dto.YoloRequest;
import kr.co.cudo.authoring.common.client.dto.YoloResponse;
import kr.co.cudo.authoring.common.client.dto.YoloTrackRequest;
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

class AiServerClientTest {

    private MockWebServer server;
    private RetryRegistry retryRegistry;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        // 테스트 단축을 위해 재시도 1회만 (max-attempts=1 = 재시도 없음)
        retryRegistry = RetryRegistry.of(RetryConfig.custom().maxAttempts(1).build());
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    @DisplayName("AiServerClient_타임아웃_시_CircuitBreaker_OPEN_전환")
    void circuitBreakerOpensOnFailures() {
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(
                io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.custom()
                        .failureRateThreshold(50)
                        .slidingWindowSize(4)
                        .minimumNumberOfCalls(4)
                        .build()
        );
        CircuitBreaker batch = registry.circuitBreaker(AiWorkload.BATCH.circuitName());
        CircuitBreaker interactive = registry.circuitBreaker(AiWorkload.INTERACTIVE.circuitName());

        for (int i = 0; i < 4; i++) {
            server.enqueue(new MockResponse().setResponseCode(500));
        }

        AiServerClient client = newClient(batch, interactive);

        // 인자 없는 호출은 화면 경로다(기본값).
        for (int i = 0; i < 4; i++) {
            try {
                client.predictYolo(new YoloRequest("frame.jpg", 0.4, 1280, 0.5)).block(Duration.ofSeconds(2));
            } catch (Exception ignored) {
                // 의도적 실패
            }
        }

        assertThat(interactive.getState()).isIn(CircuitBreaker.State.OPEN, CircuitBreaker.State.HALF_OPEN);
        // ★ 화면 경로가 연달아 실패해도 배치 서킷은 그대로다 — 두 축이 실제로 갈려 있다는 증거.
        assertThat(batch.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    /** 용도별 서킷 2개를 주입한 클라이언트. 객체 검증 경로는 이 시험의 축이 아니라 화면 것을 재사용한다. */
    private AiServerClient newClient(CircuitBreaker batch, CircuitBreaker interactive) {
        WebClient webClient = WebClient.builder()
                .baseUrl(server.url("/").toString())
                .build();
        return new AiServerClient(webClient, batch, interactive, interactive, retryRegistry);
    }

    private static MockResponse yoloOk() {
        return new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"detections\": [], \"mock\": false, \"source\": \"model\"}");
    }

    @Test
    @DisplayName("배치_서킷이_열려_있어도_화면_요청은_통과한다")
    void batchCircuitOpenDoesNotBlockInteractive() {
        CircuitBreaker batch = CircuitBreakerRegistry.ofDefaults()
                .circuitBreaker(AiWorkload.BATCH.circuitName());
        CircuitBreaker interactive = CircuitBreakerRegistry.ofDefaults()
                .circuitBreaker(AiWorkload.INTERACTIVE.circuitName());
        batch.transitionToOpenState();

        server.enqueue(yoloOk());

        YoloResponse response = newClient(batch, interactive)
                .predictYolo(new YoloRequest("frame.jpg", 0.4, 1280, 0.5))
                .block(Duration.ofSeconds(2));

        assertThat(response).isNotNull();
        assertThat(batch.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    @DisplayName("화면_서킷이_열려_있어도_배치_요청은_통과한다")
    void interactiveCircuitOpenDoesNotBlockBatch() {
        CircuitBreaker batch = CircuitBreakerRegistry.ofDefaults()
                .circuitBreaker(AiWorkload.BATCH.circuitName());
        CircuitBreaker interactive = CircuitBreakerRegistry.ofDefaults()
                .circuitBreaker(AiWorkload.INTERACTIVE.circuitName());
        interactive.transitionToOpenState();

        server.enqueue(yoloOk());

        YoloResponse response = newClient(batch, interactive)
                .predictYolo(new YoloRequest("frame.jpg", 0.4, 1280, 0.5), AiWorkload.BATCH)
                .block(Duration.ofSeconds(2));

        assertThat(response).isNotNull();
        assertThat(interactive.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    /**
     * ★이 CO 의 백엔드↔추론서버 <b>단일 계약</b>을 지키는 시험이다.
     *
     * <p>헤더를 싣는 배선이 사라지면 추론서버가 모든 요청을 화면 슬롯으로 받아 <b>배치와 화면이 다시
     * 한 줄에 선다</b> — 배치 대기 뒤에 화면 요청이 줄서던 원래 형상으로 조용히 되돌아간다.
     * 그런데 그 회귀는 <b>기능으로 드러나지 않는다</b>(응답은 정상이고 배치가 밀려 있을 때만 느려진다).
     * 그래서 요청에 실제로 무엇이 실렸는지를 보는 이 시험 말고는 잡을 방법이 없다.
     */
    @Test
    @DisplayName("배치_용도는_요청에_X_Workload_헤더를_싣는다")
    void batchCallSendsWorkloadHeader() throws Exception {
        server.enqueue(yoloOk());
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();

        newClient(registry.circuitBreaker(AiWorkload.BATCH.circuitName()),
                registry.circuitBreaker(AiWorkload.INTERACTIVE.circuitName()))
                .predictYolo(new YoloRequest("frame.jpg", 0.4, 1280, 0.5), AiWorkload.BATCH)
                .block(Duration.ofSeconds(2));

        RecordedRequest recorded = server.takeRequest();
        assertThat(recorded.getHeader(AiWorkload.HEADER_NAME)).isEqualTo("batch");
    }

    /**
     * 화면 경로는 헤더를 <b>싣지 않는다</b> — 값을 지어 붙이면 추론서버 판정이 「정확히 batch」
     * 하나에서 늘어나고, 오타·대소문자 차이가 조용히 배치로 새는 문이 열린다.
     */
    @Test
    @DisplayName("화면_용도는_X_Workload_헤더를_싣지_않는다")
    void interactiveCallOmitsWorkloadHeader() throws Exception {
        server.enqueue(yoloOk());
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();

        newClient(registry.circuitBreaker(AiWorkload.BATCH.circuitName()),
                registry.circuitBreaker(AiWorkload.INTERACTIVE.circuitName()))
                .predictYolo(new YoloRequest("frame.jpg", 0.4, 1280, 0.5))
                .block(Duration.ofSeconds(2));

        RecordedRequest recorded = server.takeRequest();
        assertThat(recorded.getHeader(AiWorkload.HEADER_NAME)).isNull();
    }

    @Test
    @DisplayName("서킷_이름은_AiWorkload_헬퍼_한_곳이_만든다")
    void circuitNamesComeFromSingleHelper() {
        assertThat(AiWorkload.BATCH.circuitName()).isEqualTo("ai-batch");
        assertThat(AiWorkload.INTERACTIVE.circuitName()).isEqualTo("ai-interactive");
        // 이중화가 들어오면 노드 축이 이 뒤에 붙는다(ai-batch-{nodeId}). nodeId 에 하이픈이 들어가면
        // 어디서 갈리는지 복원되지 않으므로 [a-z0-9]+ 로 제한하기로 합의돼 있다.
        assertThat(AiWorkload.BATCH.circuitName()).doesNotContain("_");
        assertThat(AiWorkload.INTERACTIVE.headerValue()).isNull();
        assertThat(AiWorkload.BATCH.headerValue()).isEqualTo("batch");
        assertThat(AiWorkload.defaultWorkload()).isEqualTo(AiWorkload.INTERACTIVE);
    }

    @Test
    @DisplayName("AiServerClient_track_호출시_정상_응답_반환")
    void trackReturnsResponse() {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {
                          "track_id": "track-001",
                          "polygon": [[10.0, 10.0], [50.0, 10.0], [50.0, 50.0], [10.0, 50.0]],
                          "score": 0.91
                        }
                        """));

        CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
        AiServerClient client = newClient(
                registry.circuitBreaker(AiWorkload.BATCH.circuitName()),
                registry.circuitBreaker(AiWorkload.INTERACTIVE.circuitName()));

        Sam2TrackRequest request = new Sam2TrackRequest(
                "track-001",
                "prev-b64",
                "next-b64",
                List.of(List.of(10.0, 10.0), List.of(50.0, 10.0), List.of(50.0, 50.0), List.of(10.0, 50.0))
        );

        Sam2TrackResponse response = client.track(request).block(Duration.ofSeconds(2));

        assertThat(response).isNotNull();
        assertThat(response.trackId()).isEqualTo("track-001");
        assertThat(response.polygon()).hasSize(4);
        assertThat(response.score()).isEqualTo(0.91);
    }

    // --- Phase 3: predictYoloTrack 신규 메서드 검증 ---

    @Test
    @DisplayName("predictYoloTrack_는_infer_yolo_track_으로_POST_요청")
    void predictYoloTrackPostsToTrackEndpoint() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {
                          "detections": [
                            {"label": "person", "points": [10.0, 10.0, 50.0, 50.0], "score": 0.9, "track_id": 7}
                          ],
                          "mock": false,
                          "source": "model"
                        }
                        """));

        CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
        AiServerClient client = newClient(
                registry.circuitBreaker(AiWorkload.BATCH.circuitName()),
                registry.circuitBreaker(AiWorkload.INTERACTIVE.circuitName()));

        YoloTrackRequest request = new YoloTrackRequest(
                "IMGB64", "clip-77", 3, 0.4, 1280, 0.5);

        YoloResponse response = client.predictYoloTrack(request).block(Duration.ofSeconds(2));

        assertThat(response).isNotNull();
        assertThat(response.detections()).hasSize(1);
        assertThat(response.detections().get(0).trackId()).isEqualTo(7);
        assertThat(response.detections().get(0).label()).isEqualTo("person");

        RecordedRequest recorded = server.takeRequest();
        assertThat(recorded.getMethod()).isEqualTo("POST");
        assertThat(recorded.getPath()).isEqualTo("/infer/yolo/track");
        String body = recorded.getBody().readUtf8();
        assertThat(body).contains("\"image_b64\":\"IMGB64\"");
        assertThat(body).contains("\"clip_id\":\"clip-77\"");
        assertThat(body).contains("\"frame_index\":3");
        assertThat(body).contains("\"conf_threshold\":0.4");
        assertThat(body).contains("\"imgsz\":1280");
        assertThat(body).contains("\"iou\":0.5");
    }
}
