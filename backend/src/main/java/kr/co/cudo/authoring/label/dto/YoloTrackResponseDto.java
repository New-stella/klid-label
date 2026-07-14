package kr.co.cudo.authoring.label.dto;

import java.util.List;

/**
 * YOLO 객체 트랙 추론 결과 (순수 추론 프록시 — DB 저장 없음).
 *
 * <p>프레임별 검출 결과를 순서대로 반환한다. FE 가 결과를 받아
 * 기존 {@code PUT /v1/frames/{srcSn}/labels} 로 저장한다.
 *
 * <ul>
 *   <li>frames[].srcSn       : 프레임 PK</li>
 *   <li>frames[].frameIndex  : 시퀀스 내 순서(0-base, 0=리셋 프레임)</li>
 *   <li>detections[].points  : [x1, y1, x2, y2]</li>
 *   <li>detections[].trackId : ai-server 트래커 부여 객체 ID (null 허용)</li>
 * </ul>
 */
public record YoloTrackResponseDto(List<FrameDetections> frames) {

    public record FrameDetections(
            Long srcSn,
            int frameIndex,
            List<Detected> detections
    ) {
    }

    public record Detected(
            String label,
            List<Double> points,
            Double score,
            Integer trackId
    ) {
    }
}
