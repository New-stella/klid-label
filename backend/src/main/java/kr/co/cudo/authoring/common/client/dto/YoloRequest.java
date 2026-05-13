package kr.co.cudo.authoring.common.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * ai-server YOLO 추론 요청.
 *
 * 정합 대상: POST /infer/yolo/predict
 *  - image_b64: base64 인코딩 이미지(jpeg/png)
 *  - conf_threshold: 신뢰도 임계값 (0.0 ~ 1.0)
 *  - imgsz: 추론 입력 해상도(px), 기본 1280 (Phase 1)
 *  - iou: NMS IoU 임계값 (0.0 ~ 1.0), 기본 0.5 (Phase 1)
 */
public record YoloRequest(
        @JsonProperty("image_b64") String imageB64,
        @JsonProperty("conf_threshold") Double confThreshold,
        @JsonProperty("imgsz") Integer imgsz,
        @JsonProperty("iou") Double iou
) {
    /**
     * 구버전 호출자 호환 — 기본값(0.4, 1280, 0.5) 로 위임.
     * Phase 1 에서 운영 파라미터화 됐으므로 새 호출자는 4-arg 생성자 사용 권장.
     */
    @Deprecated
    public YoloRequest(String imageB64) {
        this(imageB64, 0.4, 1280, 0.5);
    }
}
