package kr.co.cudo.authoring.common.client.dto;

import java.util.List;

/**
 * ai-server YOLO 추론 응답.
 *
 *  - detections[].label : 클래스 라벨
 *  - detections[].points: [x1, y1, x2, y2]
 *  - detections[].score : 신뢰도 (0.0 ~ 1.0)
 */
public record YoloResponse(List<Detection> detections) {
    public record Detection(String label, List<Double> points, double score) {}
}
