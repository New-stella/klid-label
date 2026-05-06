package kr.co.cudo.authoring.common.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * ai-server VLM 객체 검증 요청 (V1.7 — 객체 검증 한정).
 *
 * 정합 대상: POST /infer/vlm/verify-objects
 *  - image_b64: base64 이미지
 *  - objects  : 검증 대상 객체 (YOLO/SAM2 검출 결과)
 */
public record VlmVerifyRequest(
        @JsonProperty("image_b64") String imageB64,
        List<ObjectToVerify> objects
) {
    public record ObjectToVerify(
            @JsonProperty("obj_id") String objId,
            @JsonProperty("expected_label") String expectedLabel,
            List<Double> bbox
    ) {}
}
