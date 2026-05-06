package kr.co.cudo.authoring.label.dto;

import java.util.List;

/**
 * SAM2 Track 결과.
 * - tracked: 후속 프레임별 (srcSn, trackId, points, score) 매핑.
 */
public record Sam2TrackResponseDto(List<TrackedItem> tracked) {

    public record TrackedItem(
            Long srcSn,
            String trackId,
            String label,
            List<List<Double>> points,
            double score
    ) {
    }
}
