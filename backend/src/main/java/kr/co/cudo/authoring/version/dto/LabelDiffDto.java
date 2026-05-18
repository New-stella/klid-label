package kr.co.cudo.authoring.version.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * FE {@code LabelDiff} 와 1:1 매핑되는 라벨 단위 diff DTO.
 *
 * <p>Phase 8 보강 (2026-05-19) — Gitea compare API 의 파일 단위 diff 만으로는
 * 라벨링 화면 작업이력 패널에서 "어떤 라벨이 추가/수정/삭제되었는지" 를 알 수 없다.
 * BE 가 두 SHA 의 라벨 JSON 을 직접 파싱·비교하여 라벨 단위 diff 를 산출한다.
 *
 * <ul>
 *   <li>{@code ADDED}    — toSha 에만 존재 (after only)
 *   <li>{@code REMOVED}  — fromSha 에만 존재 (before only)
 *   <li>{@code MODIFIED} — 양쪽 id 존재 + shape/label 차이 (before + after)
 * </ul>
 *
 * <p>{@code objectId} 는 라벨 PK(LS_DATA_LBL.LBL_SN) 의 문자열 표현.
 * 같은 srcSn 내에서 고유하므로 라벨 추적에 충분하다.
 * Gitea 의 SHA 비교 결과와 무관하게 안전하다 (외부 입력 아님).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LabelDiffDto(
        DiffType type,
        Integer frameId,
        String objectId,
        ShapeDto before,
        ShapeDto after
) {

    public enum DiffType {
        ADDED,
        MODIFIED,
        REMOVED
    }

    /** FE Shape 타입과 1:1. BBOX 는 4 좌표(left/top/right/bottom), POLYGON 은 평탄 points 배열. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ShapeDto(
            String type,
            Double left,
            Double top,
            Double right,
            Double bottom,
            List<Double> points
    ) {
        /** 라벨 points 가 정확히 2점이면 BBOX, 3점 이상이면 POLYGON 으로 추정. */
        public static ShapeDto fromPoints(String lblTypeCd, List<List<Double>> points) {
            if (points == null || points.isEmpty()) {
                return null;
            }
            // BBOX: 정확히 2점 (좌상 + 우하) 또는 lblTypeCd 가 BBOX
            boolean isBbox = "BBOX".equalsIgnoreCase(lblTypeCd) || points.size() == 2;
            if (isBbox && points.size() == 2) {
                List<Double> p0 = points.get(0);
                List<Double> p1 = points.get(1);
                if (p0.size() >= 2 && p1.size() >= 2) {
                    return new ShapeDto("BBOX",
                            Math.min(p0.get(0), p1.get(0)),
                            Math.min(p0.get(1), p1.get(1)),
                            Math.max(p0.get(0), p1.get(0)),
                            Math.max(p0.get(1), p1.get(1)),
                            null);
                }
            }
            // POLYGON: 평탄 좌표 배열로 변환 (FE PolygonShape.points 와 정합)
            List<Double> flat = new java.util.ArrayList<>(points.size() * 2);
            for (List<Double> pt : points) {
                if (pt.size() >= 2) {
                    flat.add(pt.get(0));
                    flat.add(pt.get(1));
                }
            }
            return new ShapeDto("POLYGON", null, null, null, null, flat);
        }
    }
}
