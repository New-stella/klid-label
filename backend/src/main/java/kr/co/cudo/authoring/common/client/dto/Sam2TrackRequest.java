package kr.co.cudo.authoring.common.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * ai-server SAM2 track 요청.
 *
 * 정합 대상: POST /infer/sam2/track
 *  - track_id        : 이전 프레임에서 부여된 트랙 ID
 *  - prev_image_b64  : 이전 프레임 base64 이미지
 *  - next_image_b64  : 다음 프레임 base64 이미지
 *  - prev_polygon    : 이전 프레임 폴리곤 [[x, y], ...] (최소 3점)
 */
public record Sam2TrackRequest(
        @JsonProperty("track_id") String trackId,
        @JsonProperty("prev_image_b64") String prevImageB64,
        @JsonProperty("next_image_b64") String nextImageB64,
        @JsonProperty("prev_polygon") List<List<Double>> prevPolygon
) {
}
