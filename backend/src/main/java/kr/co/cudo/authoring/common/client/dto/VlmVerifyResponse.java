package kr.co.cudo.authoring.common.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * ai-server VLM 객체 검증 응답.
 *
 *  - results[].obj_id        : 요청한 obj_id 그대로 반사
 *  - results[].expected_label: 요청한 expected_label 반사
 *  - results[].verified      : VLM이 라벨 정합성을 확인했는지
 *  - results[].confidence    : 신뢰도 (0.0 ~ 1.0)
 */
public record VlmVerifyResponse(List<ObjectVerification> results) {
    public record ObjectVerification(
            @JsonProperty("obj_id") String objId,
            @JsonProperty("expected_label") String expectedLabel,
            boolean verified,
            double confidence
    ) {}
}
