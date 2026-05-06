package kr.co.cudo.authoring.common.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * ai-server SAM2 track 응답.
 *
 *  - track_id : 입력과 동일한 트랙 ID (전파 유지)
 *  - polygon  : 다음 프레임에서의 폐곡선 좌표 [[x, y], ...]
 *  - score    : 신뢰도 (0.0 ~ 1.0)
 */
public record Sam2TrackResponse(
        @JsonProperty("track_id") String trackId,
        List<List<Double>> polygon,
        double score
) {
}
