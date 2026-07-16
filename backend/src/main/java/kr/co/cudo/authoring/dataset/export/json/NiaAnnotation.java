package kr.co.cudo.authoring.dataset.export.json;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * NIA COCO 확장 {@code annotations} 항목 (xlsx v1.3) — 한 라벨(LS_DATA_LBL) 1건.
 *
 * <p>타입별로 셋 중 하나만 채워진다: {@code bbox}([x,y,w,h]) / {@code polygon}([[x,y,x,y,…]]) /
 * {@code keypoints}([[x,y,v]×17]). 나머지는 null(키 유지).
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record NiaAnnotation(
        @JsonProperty("id") Integer id,
        @JsonProperty("image_id") Integer imageId,
        @JsonProperty("category_id") String categoryId,
        @JsonProperty("track_id") String trackId,
        @JsonProperty("bbox") List<Number> bbox,
        @JsonProperty("polygon") List<List<Number>> polygon,
        @JsonProperty("keypoints") List<List<Number>> keypoints,
        @JsonProperty("text") String text
) {
}
