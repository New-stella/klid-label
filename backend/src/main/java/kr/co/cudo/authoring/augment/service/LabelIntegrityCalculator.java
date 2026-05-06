package kr.co.cudo.authoring.augment.service;

import kr.co.cudo.authoring.label.dto.LabelItemDto;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Phase 9 — 라벨 무결성 점수 계산기.
 *
 * <p>외부 증강 시스템(SFR-07)이 생성한 라벨이 원본과 얼마나 보존되었는지를 0~100% 로 산출.
 *
 * <ul>
 *   <li>좌표 보존 (가중 0.7): 원본 BBOX 와 IoU > 0.9 인 증강 라벨 비율</li>
 *   <li>카테고리 보존 (가중 0.3): 라벨 텍스트(label) 동일 비율</li>
 * </ul>
 *
 * <p>BBOX 가 아닌 라벨 타입(POLYGON 등)은 좌표 비교에서 폴리곤의 바운딩 박스(min/max)로 환산하여 비교한다.
 * 원본/증강 어느 쪽이든 비어 있으면 결과는 0.
 */
@Component
public class LabelIntegrityCalculator {

    private static final double IOU_THRESHOLD = 0.9;
    private static final double WEIGHT_COORD    = 0.7;
    private static final double WEIGHT_CATEGORY = 0.3;

    /**
     * @return 0.00 ~ 100.00 (소수점 둘째 자리 반올림)
     */
    public BigDecimal calculate(List<LabelItemDto> original, List<LabelItemDto> augmented) {
        if (original == null || augmented == null || original.isEmpty() || augmented.isEmpty()) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }

        double coordPreserved = coordinatePreservationRatio(original, augmented);
        double categoryPreserved = categoryPreservationRatio(original, augmented);

        double weighted = (coordPreserved * WEIGHT_COORD + categoryPreserved * WEIGHT_CATEGORY) * 100.0;
        return BigDecimal.valueOf(weighted).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 원본 라벨별로 증강 라벨 중 IoU > 0.9 매칭이 1건이라도 있으면 보존된 것으로 간주.
     */
    private double coordinatePreservationRatio(List<LabelItemDto> original, List<LabelItemDto> augmented) {
        int preserved = 0;
        for (LabelItemDto src : original) {
            double[] srcBox = toBoundingBox(src);
            if (srcBox == null) continue;
            for (LabelItemDto aug : augmented) {
                double[] augBox = toBoundingBox(aug);
                if (augBox == null) continue;
                if (iou(srcBox, augBox) > IOU_THRESHOLD) {
                    preserved++;
                    break;
                }
            }
        }
        return (double) preserved / original.size();
    }

    /**
     * 원본 라벨 텍스트가 증강 라벨 텍스트 집합에 포함되어 있는 비율.
     */
    private double categoryPreservationRatio(List<LabelItemDto> original, List<LabelItemDto> augmented) {
        int preserved = 0;
        for (LabelItemDto src : original) {
            for (LabelItemDto aug : augmented) {
                if (src.label() != null && src.label().equals(aug.label())) {
                    preserved++;
                    break;
                }
            }
        }
        return (double) preserved / original.size();
    }

    /**
     * 점 목록을 [xMin, yMin, xMax, yMax] 로 변환 (BBOX/POLYGON/SEGMENT 공통).
     * 점이 부족하거나 좌표가 누락되면 null.
     */
    private double[] toBoundingBox(LabelItemDto item) {
        if (item == null || item.points() == null || item.points().isEmpty()) return null;
        double xMin = Double.POSITIVE_INFINITY, yMin = Double.POSITIVE_INFINITY;
        double xMax = Double.NEGATIVE_INFINITY, yMax = Double.NEGATIVE_INFINITY;
        for (List<Double> p : item.points()) {
            if (p == null || p.size() < 2 || p.get(0) == null || p.get(1) == null) continue;
            double x = p.get(0), y = p.get(1);
            if (x < xMin) xMin = x;
            if (y < yMin) yMin = y;
            if (x > xMax) xMax = x;
            if (y > yMax) yMax = y;
        }
        if (xMin == Double.POSITIVE_INFINITY) return null;
        return new double[]{xMin, yMin, xMax, yMax};
    }

    /**
     * 두 BBOX 의 IoU (Intersection over Union).
     */
    private double iou(double[] a, double[] b) {
        double interX1 = Math.max(a[0], b[0]);
        double interY1 = Math.max(a[1], b[1]);
        double interX2 = Math.min(a[2], b[2]);
        double interY2 = Math.min(a[3], b[3]);
        double interW = Math.max(0, interX2 - interX1);
        double interH = Math.max(0, interY2 - interY1);
        double interArea = interW * interH;
        double areaA = Math.max(0, a[2] - a[0]) * Math.max(0, a[3] - a[1]);
        double areaB = Math.max(0, b[2] - b[0]) * Math.max(0, b[3] - b[1]);
        double union = areaA + areaB - interArea;
        if (union <= 0) return 0;
        return interArea / union;
    }
}
