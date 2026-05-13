package kr.co.cudo.authoring.common.client.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 3: YoloResponse.Detection 의 trackId snake_case 매핑 검증.
 *
 * <p>ai-server `/infer/yolo/track` 응답은 {@code track_id: int|null} 을 포함한다.
 * Jackson 이 snake_case → camelCase 매핑을 수행하는지, 누락 시 null 로 안전 처리하는지 확인.
 */
class YoloResponseTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("Detection_역직렬화_시_track_id_가_trackId_로_매핑")
    void detectionDeserializesTrackId() throws Exception {
        String json = """
                {
                  "label": "person",
                  "points": [10.0, 20.0, 30.0, 40.0],
                  "score": 0.92,
                  "track_id": 42
                }
                """;

        YoloResponse.Detection d = objectMapper.readValue(json, YoloResponse.Detection.class);

        assertThat(d.label()).isEqualTo("person");
        assertThat(d.score()).isEqualTo(0.92);
        assertThat(d.points()).containsExactly(10.0, 20.0, 30.0, 40.0);
        assertThat(d.trackId()).isEqualTo(42);
    }

    @Test
    @DisplayName("Detection_track_id_누락_시_trackId_null")
    void detectionTrackIdMissingDefaultsNull() throws Exception {
        String json = """
                {
                  "label": "car",
                  "points": [1.0, 2.0, 3.0, 4.0],
                  "score": 0.7
                }
                """;

        YoloResponse.Detection d = objectMapper.readValue(json, YoloResponse.Detection.class);

        assertThat(d.label()).isEqualTo("car");
        assertThat(d.trackId()).isNull();
    }

    @Test
    @DisplayName("Detection_track_id_null_명시_시_trackId_null")
    void detectionTrackIdExplicitNull() throws Exception {
        String json = """
                {
                  "label": "bus",
                  "points": [0.0, 0.0, 1.0, 1.0],
                  "score": 0.3,
                  "track_id": null
                }
                """;

        YoloResponse.Detection d = objectMapper.readValue(json, YoloResponse.Detection.class);

        assertThat(d.trackId()).isNull();
    }

    @Test
    @DisplayName("YoloResponse_detections_배열_안의_Detection_도_track_id_매핑")
    void yoloResponseDetectionsTrackIdMapping() throws Exception {
        String json = """
                {
                  "detections": [
                    {"label": "person", "points": [1.0, 2.0, 3.0, 4.0], "score": 0.9, "track_id": 1},
                    {"label": "person", "points": [5.0, 6.0, 7.0, 8.0], "score": 0.8, "track_id": 2}
                  ],
                  "mock": false,
                  "source": "model"
                }
                """;

        YoloResponse resp = objectMapper.readValue(json, YoloResponse.class);

        assertThat(resp.detections()).hasSize(2);
        assertThat(resp.detections().get(0).trackId()).isEqualTo(1);
        assertThat(resp.detections().get(1).trackId()).isEqualTo(2);
    }
}
