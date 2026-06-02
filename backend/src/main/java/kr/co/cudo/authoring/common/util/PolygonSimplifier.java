package kr.co.cudo.authoring.common.util;

import java.util.ArrayList;
import java.util.List;

/**
 * FEAT-007 (SFR-08-03) 라벨링 정밀도 — 경계 세밀함(폴리곤 단순화) 유틸.
 * <p>
 * Ramer–Douglas–Peucker 알고리즘으로 폴리곤/폴리라인 점 수를 감소시키되
 * 전체 형태(시작/끝점, 코너)는 보존한다. epsilon(=POLYGON_SIMPLIFY_TOLERANCE, px)
 * 값이 클수록 더 많은 점이 제거되어 경계가 거칠어진다.
 * <p>
 * CVAT portable-modules/06 좌표 유틸 스타일 — stateless, 입력 불변.
 */
public final class PolygonSimplifier {

    private PolygonSimplifier() {}

    /**
     * Douglas-Peucker 단순화.
     *
     * @param points  원본 좌표 (null/빈/2점 이하면 그대로 반환)
     * @param epsilon 허용 오차(px). 0 이하면 단순화하지 않고 원본 반환.
     * @return 단순화된 좌표 (시작/끝점은 항상 보존)
     */
    public static List<Point> simplify(List<Point> points, double epsilon) {
        if (points == null || points.isEmpty()) {
            return new ArrayList<>();
        }
        if (points.size() <= 2 || epsilon <= 0.0) {
            return new ArrayList<>(points);
        }

        boolean[] keep = new boolean[points.size()];
        keep[0] = true;
        keep[points.size() - 1] = true;
        douglasPeucker(points, 0, points.size() - 1, epsilon, keep);

        List<Point> result = new ArrayList<>();
        for (int i = 0; i < points.size(); i++) {
            if (keep[i]) {
                result.add(points.get(i));
            }
        }
        return result;
    }

    private static void douglasPeucker(List<Point> points, int start, int end,
                                       double epsilon, boolean[] keep) {
        if (end <= start + 1) {
            return;
        }
        double maxDist = -1.0;
        int index = -1;
        Point a = points.get(start);
        Point b = points.get(end);
        for (int i = start + 1; i < end; i++) {
            double d = perpendicularDistance(points.get(i), a, b);
            if (d > maxDist) {
                maxDist = d;
                index = i;
            }
        }
        if (maxDist > epsilon && index != -1) {
            keep[index] = true;
            douglasPeucker(points, start, index, epsilon, keep);
            douglasPeucker(points, index, end, epsilon, keep);
        }
    }

    /** 점 p 와 선분 a-b 사이의 수직 거리. a==b 면 점 간 유클리드 거리. */
    private static double perpendicularDistance(Point p, Point a, Point b) {
        double dx = b.x() - a.x();
        double dy = b.y() - a.y();
        double lenSq = dx * dx + dy * dy;
        if (lenSq == 0.0) {
            double ex = p.x() - a.x();
            double ey = p.y() - a.y();
            return Math.sqrt(ex * ex + ey * ey);
        }
        double area2 = Math.abs(dy * p.x() - dx * p.y() + b.x() * a.y() - b.y() * a.x());
        return area2 / Math.sqrt(lenSq);
    }
}
