package kr.co.cudo.authoring.dataset.export.json;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * NIA COCO 확장 {@code categories} 항목 (xlsx v1.3) — 사용된 라벨(LS_LABEL) 1건.
 *
 * <p>{@code keypoints}(관절명)/{@code skeleton}(엣지)는 keypoints 타입에만 채워진다(COCO-17).
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record NiaCategory(
        @JsonProperty("id") String id,
        @JsonProperty("name") String name,
        @JsonProperty("type") String type,
        @JsonProperty("supercategory") String supercategory,
        @JsonProperty("keypoints") List<String> keypoints,
        @JsonProperty("skeleton") List<List<Integer>> skeleton
) {
}
