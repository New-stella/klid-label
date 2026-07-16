package kr.co.cudo.authoring.dataset.export.json;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * NIA COCO 확장 {@code dataset} 블록 (xlsx v1.3).
 *
 * <p>{@code identifier} 는 영상 식별자(=video_id=RAW_SN 문자열)이다.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record NiaDataset(
        @JsonProperty("identifier") String identifier,
        @JsonProperty("name") String name,
        @JsonProperty("src_path") String srcPath,
        @JsonProperty("label_path") String labelPath
) {
}
