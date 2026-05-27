package kr.co.cudo.authoring.common.client.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 1: YoloRequest record 의 신규 imgsz/iou 필드 + 2-arg 위임 검증.
 */
class YoloRequestTest {

    @Test
    @DisplayName("YoloRequest_4arg_생성자는_모든_필드_저장")
    void fourArgConstructorRetainsAllFields() {
        YoloRequest req = new YoloRequest("BASE64", 0.45, 960, 0.6);
        assertThat(req.imageB64()).isEqualTo("BASE64");
        assertThat(req.confThreshold()).isEqualTo(0.45);
        assertThat(req.imgsz()).isEqualTo(960);
        assertThat(req.iou()).isEqualTo(0.6);
    }

    @Test
    @DisplayName("YoloRequest_기본값_조합_0_4_1280_0_5_정상_저장")
    void defaultValueCombinationStored() {
        YoloRequest req = new YoloRequest("BASE64", 0.4, 1280, 0.5);
        assertThat(req.imageB64()).isEqualTo("BASE64");
        assertThat(req.confThreshold()).isEqualTo(0.4);
        assertThat(req.imgsz()).isEqualTo(1280);
        assertThat(req.iou()).isEqualTo(0.5);
    }
}
