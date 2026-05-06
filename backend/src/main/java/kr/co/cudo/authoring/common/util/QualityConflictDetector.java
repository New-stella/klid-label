package kr.co.cudo.authoring.common.util;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Phase 10 — 어노테이션 품질 충돌 감지 (CVAT portable-modules/09 포팅).
 *
 * <p>두 라벨 집합(A=GT, B=DS) 을 IoU 기반으로 매칭한 후 4가지 유형으로 분류:
 * <pre>
 *   MISSING            — A 의 라벨이 B 에 없음
 *   EXTRA              — B 의 라벨이 A 에 없음
 *   MISMATCHING_LABEL  — 매칭 쌍의 라벨명 불일치
 *   LOW_OVERLAP        — IoU 가 low_overlap_threshold(=0.8) 미만 (warning)
 * </pre>
 *
 * <p>매칭 알고리즘:
 * <ul>
 *   <li>본 V1 은 <b>greedy IoU 매칭</b> 사용 (최대 IoU 쌍을 순서대로 확정).
 *       구현 단순성 + 본 도메인의 라벨 개수가 프레임당 수십 개 수준으로 작아 충분.
 *   <li>후속 최적화 가능: O(N³) 헝가리안(commons-math3 {@code KuhnMunkresAssignment}) 도입.
 *   <li>거리 임계 미달(IoU < {@code iouThreshold})은 매칭 후보에서 제외 — CVAT 의 {@code dist_thresh} 동작.
 * </ul>
 *
 * <p>Adapted from CVAT (https://github.com/cvat-ai/cvat) apps/quality_control — MIT.
 */
public final class QualityConflictDetector {

    /** CVAT ComparisonParameters 기본값. iou < 이 값은 매칭 후보 제외. */
    public static final double DEFAULT_IOU_THRESHOLD = 0.4;
    /** CVAT ComparisonParameters 기본값. iou < 이 값은 LOW_OVERLAP warning. */
    public static final double DEFAULT_LOW_OVERLAP_THRESHOLD = 0.8;

    private QualityConflictDetector() {}

    /**
     * 기본 임계값으로 검사 (iou=0.4, low_overlap=0.8).
     */
    public static List<AnnotationConflict> detect(List<LabelShape> a, List<LabelShape> b) {
        return detect(a, b, DEFAULT_IOU_THRESHOLD, DEFAULT_LOW_OVERLAP_THRESHOLD);
    }

    /**
     * 두 라벨 집합 비교 → 충돌 리스트.
     *
     * @param a                       GT (정답) 라벨 리스트
     * @param b                       DS (작업자) 라벨 리스트
     * @param iouThreshold            매칭 후보 IoU 하한 (이 미만은 매칭 불가)
     * @param lowOverlapThreshold     LOW_OVERLAP 경고 IoU 하한 (이 미만이면 warning)
     */
    public static List<AnnotationConflict> detect(List<LabelShape> a,
                                                  List<LabelShape> b,
                                                  double iouThreshold,
                                                  double lowOverlapThreshold) {
        List<LabelShape> aList = a == null ? List.of() : a;
        List<LabelShape> bList = b == null ? List.of() : b;

        // 1. greedy 매칭 — IoU 가 가장 큰 쌍부터 확정 (CVAT 헝가리안 단순화)
        List<int[]> pairs = greedyMatch(aList, bList, iouThreshold);
        Set<Integer> matchedA = new HashSet<>();
        Set<Integer> matchedB = new HashSet<>();
        for (int[] pair : pairs) {
            matchedA.add(pair[0]);
            matchedB.add(pair[1]);
        }

        // 2. 매칭 결과 → 충돌 분류
        List<AnnotationConflict> conflicts = new ArrayList<>();
        for (int[] pair : pairs) {
            LabelShape ga = aList.get(pair[0]);
            LabelShape db = bList.get(pair[1]);
            double iou = bboxIou(ga, db);
            if (!labelsMatch(ga.label(), db.label())) {
                conflicts.add(new AnnotationConflict(
                        ConflictType.MISMATCHING_LABEL, ga.id(), db.id(), iou));
            } else if (iou < lowOverlapThreshold) {
                conflicts.add(new AnnotationConflict(
                        ConflictType.LOW_OVERLAP, ga.id(), db.id(), iou));
            }
        }
        for (int i = 0; i < aList.size(); i++) {
            if (!matchedA.contains(i)) {
                conflicts.add(new AnnotationConflict(
                        ConflictType.MISSING, aList.get(i).id(), null, null));
            }
        }
        for (int j = 0; j < bList.size(); j++) {
            if (!matchedB.contains(j)) {
                conflicts.add(new AnnotationConflict(
                        ConflictType.EXTRA, null, bList.get(j).id(), null));
            }
        }
        return conflicts;
    }

    // ============================================================
    //  내부 — greedy IoU 매칭
    // ============================================================

    private static List<int[]> greedyMatch(List<LabelShape> a, List<LabelShape> b, double iouThreshold) {
        List<int[]> pairs = new ArrayList<>();
        if (a.isEmpty() || b.isEmpty()) {
            return pairs;
        }
        boolean[] usedA = new boolean[a.size()];
        boolean[] usedB = new boolean[b.size()];

        // 모든 쌍의 IoU 계산 후 (i, j, iou) 트리플로 정렬
        List<double[]> triples = new ArrayList<>(a.size() * b.size());
        for (int i = 0; i < a.size(); i++) {
            for (int j = 0; j < b.size(); j++) {
                double iou = bboxIou(a.get(i), b.get(j));
                if (iou >= iouThreshold) {
                    triples.add(new double[]{i, j, iou});
                }
            }
        }
        // IoU 내림차순
        triples.sort((x, y) -> Double.compare(y[2], x[2]));

        for (double[] t : triples) {
            int i = (int) t[0];
            int j = (int) t[1];
            if (!usedA[i] && !usedB[j]) {
                usedA[i] = true;
                usedB[j] = true;
                pairs.add(new int[]{i, j});
            }
        }
        return pairs;
    }

    // ============================================================
    //  IoU 계산 — BBOX 외접 (CVAT bbox_iou)
    // ============================================================

    static double bboxIou(LabelShape a, LabelShape b) {
        double[] boxA = bbox(a.points());
        double[] boxB = bbox(b.points());
        double ix0 = Math.max(boxA[0], boxB[0]);
        double iy0 = Math.max(boxA[1], boxB[1]);
        double ix1 = Math.min(boxA[2], boxB[2]);
        double iy1 = Math.min(boxA[3], boxB[3]);
        double iw = Math.max(0.0, ix1 - ix0);
        double ih = Math.max(0.0, iy1 - iy0);
        double inter = iw * ih;
        double areaA = Math.max(0.0, (boxA[2] - boxA[0]) * (boxA[3] - boxA[1]));
        double areaB = Math.max(0.0, (boxB[2] - boxB[0]) * (boxB[3] - boxB[1]));
        double union = areaA + areaB - inter;
        return union > 0 ? inter / union : 0.0;
    }

    private static double[] bbox(List<Point> points) {
        double xMin = Double.POSITIVE_INFINITY;
        double yMin = Double.POSITIVE_INFINITY;
        double xMax = Double.NEGATIVE_INFINITY;
        double yMax = Double.NEGATIVE_INFINITY;
        for (Point p : points) {
            if (p.x() < xMin) xMin = p.x();
            if (p.y() < yMin) yMin = p.y();
            if (p.x() > xMax) xMax = p.x();
            if (p.y() > yMax) yMax = p.y();
        }
        return new double[]{xMin, yMin, xMax, yMax};
    }

    private static boolean labelsMatch(String a, String b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.equals(b);
    }

    /**
     * 비교 대상 라벨. id/label/points 만 사용 (LabelDto / LsDataLbl 모두 어댑팅 가능).
     */
    public record LabelShape(Long id, String label, List<Point> points) {
    }
}
