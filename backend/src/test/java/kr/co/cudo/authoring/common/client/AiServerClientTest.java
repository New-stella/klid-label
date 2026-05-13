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
        CircuitBreaker cb = registry.circuitBreaker("ai");

        for (int i = 0; i < 4; i++) {
            server.enqueue(new MockResponse().setResponseCode(500));
        }

        WebClient webClient = WebClient.builder()
                .baseUrl(server.url("/").toString())
                .build();
        AiServerClient client = new AiServerClient(webClient, cb, retryRegistry);

        for (int i = 0; i < 4; i++) {
            try {
                client.predictYolo(new YoloRequest("frame.jpg")).block(Duration.ofSeconds(2));
            } catch (Exception ignored) {
                // 의도적 실패
            }
        }

        assertThat(cb.getState()).isIn(CircuitBreaker.State.OPEN, CircuitBreaker.State.HALF_OPEN);
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

        CircuitBreaker cb = CircuitBreakerRegistry.ofDefaults().circuitBreaker("ai");
        WebClient webClient = WebClient.builder()
                .baseUrl(server.url("/").toString())
                .build();
        AiServerClient client = new AiServerClient(webClient, cb, retryRegistry);

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

        CircuitBreaker cb = CircuitBreakerRegistry.ofDefaults().circuitBreaker("ai");
        WebClient webClient = WebClient.builder()
                .baseUrl(server.url("/").toString())
                .build();
        AiServerClient client = new AiServerClient(webClient, cb, retryRegistry);

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
