package kr.co.cudo.authoring.dataset.export.json;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * NIA COCO 확장 {@code image} 블록 (xlsx v1.3) — 한 프레임 1건.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record NiaImage(
        @JsonProperty("id") Integer id,
        @JsonProperty("file_name") String fileName,
        @JsonProperty("width") Integer width,
        @JsonProperty("height") Integer height,
        @JsonProperty("date_captured") String dateCaptured,
        @JsonProperty("license_id") Integer licenseId,
        @JsonProperty("video_id") String videoId,
        @JsonProperty("type") String type,
        @JsonProperty("frame_num") Integer frameNum,
        @JsonProperty("anonymity") String anonymity,
        @JsonProperty("pseudonymity") String pseudonymity,
        @JsonProperty("privacy_included") String privacyIncluded,
        @JsonProperty("description") String description
) {
}
