package kr.co.cudo.authoring.common.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * ai-server YOLO 추론 응답.
 *
 *  - detections[].label   : 클래스 라벨
 *  - detections[].points  : [x1, y1, x2, y2]
 *  - detections[].score   : 신뢰도 (0.0 ~ 1.0)
 *  - detections[].trackId : (Phase 3) ultralytics 트래커가 부여한 객체 ID — null 허용.
 *                           `/infer/yolo/track` 응답에만 채워지며, `/infer/yolo/predict` 응답은 null.
 *  - mock                 : ai-server 가 mock 응답을 반환했는지 여부
 *                            (env=AI_MOCK_MODE=true 이거나 가중치 미존재 / 로드 실패 시 true)
 *  - source               : "mock" | "model"
 *  - mockReason           : mock 응답인 경우 사유 ("env_mock" | "weights_missing" | "load_failed")
 *
 * 하위 호환:
 *  - 신규 필드는 모두 기본값 false / "model" / null 로 누락된 응답에도 안전하다.
 *  - 구버전 ai-server 가 mock/source/track_id 를 미전송해도 Jackson 의 missing-field 기본값으로 매핑된다.
 */
public record YoloResponse(
        List<Detection> detections,
        boolean mock,
        String source,
        @JsonProperty("mock_reason") String mockReason
) {
    /** 구버전 호출자 호환 — mock=false, source="model" 기본. */
    public YoloResponse(List<Detection> detections) {
        this(detections, false, "model", null);
    }

    public record Detection(
            String label,
            List<Double> points,
            double score,
            @JsonProperty("track_id") Integer trackId
    ) {
        /** 4-arg 호출자 호환 — trackId=null (predict 경로 등). */
        public Detection(String label, List<Double> points, double score) {
            this(label, points, score, null);
        }
    }
}
