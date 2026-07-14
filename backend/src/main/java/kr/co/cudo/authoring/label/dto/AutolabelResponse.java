package kr.co.cudo.authoring.label.dto;

import java.util.List;

/**
 * Phase 3 — YOLO 오토라벨 수동 트리거 응답.
 *
 * @param srcSn      대상 프레임 PK
 * @param savedCount 저장된 BBOX 라벨 개수 (mock 응답이면 0)
 * @param mock       ai-server mock 모드 응답 여부 — true 면 저장하지 않고 FE 가 자동적용을 차단해야 한다
 * @param labels     저장된 라벨 요약 (FE 즉시 반영용). mock 이면 빈 리스트.
 */
public record AutolabelResponse(Long srcSn, int savedCount, boolean mock, List<Item> labels) {

    /**
     * 저장된 라벨 요약.
     *
     * @param lblSn   라벨 PK
     * @param labelId LS_LABEL FK (미매칭 시 null)
     * @param label   라벨명
     * @param points  BBOX 평탄 좌표 [x1,y1,x2,y2]
     * @param score   신뢰도 (0.0~1.0)
     * @param trackId 트래커 객체 ID (단일 프레임 트리거이므로 연속성 미보장 — null 가능)
     */
    public record Item(Long lblSn, Long labelId, String label,
                       List<Double> points, Double score, Integer trackId) {
    }
}
