package kr.co.cudo.authoring.common.client.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * ai-server SAM2 segment 요청.
 *
 * 정합 대상: POST /infer/sam2/segment
 *  - image_b64: base64 이미지
 *  - points   : [[x, y], ...] 클릭 좌표 (선택)
 *  - box      : [x1, y1, x2, y2] (선택)
 *
 * points/box 둘 중 하나는 제공해야 의미 있음.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Sam2Request(
        @JsonProperty("image_b64") String imageB64,
        List<List<Double>> points,
        List<Double> box
) {
}
