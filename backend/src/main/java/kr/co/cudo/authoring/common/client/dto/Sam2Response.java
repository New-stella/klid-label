package kr.co.cudo.authoring.common.client.dto;

import java.util.List;

/**
 * ai-server SAM2 segment 응답.
 *
 *  - polygon: [[x, y], ...] 폐곡선 좌표
 *  - score  : 신뢰도 (0.0 ~ 1.0)
 */
public record Sam2Response(List<List<Double>> polygon, double score) {
}
