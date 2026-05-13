package kr.co.cudo.authoring.common.client.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 3: YoloTrackRequest record 의 snake_case 직렬화 검증.
 *
 * <p>ai-server `/infer/yolo/track` 가 요구하는 payload 필드 — image_b64, clip_id, frame_index,
 * conf_threshold — 가 정확히 출력되는지 확인.
 */
class YoloTrackRequestTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("YoloTrackRequest_직렬화_시_snake_case_키_출력")
    void serializesSnakeCaseKeys() throws Exception {
        YoloTrackRequest req = new YoloTrackRequest(
                "BASE64DATA", "clip-77", 5, 0.4, 1280, 0.5);

        String json = objectMapper.writeValueAsString(req);
        ObjectNode node = (ObjectNode) objectMapper.readTree(json);

        assertThat(node.get("image_b64").asText()).isEqualTo("BASE64DATA");
        assertThat(node.get("clip_id").asText()).isEqualTo("clip-77");
        assertThat(node.get("frame_index").asInt()).isEqualTo(5);
        assertThat(node.get("conf_threshold").asDouble()).isEqualTo(0.4);
        assertThat(node.get("imgsz").asInt()).isEqualTo(1280);
        assertThat(node.get("iou").asDouble()).isEqualTo(0.5);
    }

    @Test
    @DisplayName("YoloTrackRequest_모든_필드_저장_확인_(record_accessor)")
    void recordAccessorsExposeFields() {
        YoloTrackRequest req = new YoloTrackRequest(
                "IMG", "clip-1", 0, 0.5, 960, 0.6);

        assertThat(req.imageB64()).isEqualTo("IMG");
        assertThat(req.clipId()).isEqualTo("clip-1");
        assertThat(req.frameIndex()).isZero();
        assertThat(req.confThreshold()).isEqualTo(0.5);
        assertThat(req.imgsz()).isEqualTo(960);
        assertThat(req.iou()).isEqualTo(0.6);
    }
}
