package kr.co.cudo.authoring.common.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * ai-server YOLO 추론 요청.
 *
 * 정합 대상: POST /infer/yolo/predict
 *  - image_b64: base64 인코딩 이미지(jpeg/png)
 *  - conf_threshold: 신뢰도 임계값 (0.0 ~ 1.0)
 */
public record YoloRequest(
        @JsonProperty("image_b64") String imageB64,
        @JsonProperty("conf_threshold") Double confThreshold
) {
    public YoloRequest(String imageB64) {
        this(imageB64, 0.25);
    }
}
