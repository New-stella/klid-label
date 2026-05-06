package kr.co.cudo.authoring.common.util;

import java.util.ArrayList;
import java.util.List;

/**
 * CVAT 좌표 변환 / 회전 자바 포팅 (portable-modules/06).
 *
 * 원본:
 *  - cvat-canvas/src/typescript/shared.ts:159-174 (rotate2DPoints)
 *
 * 표준 2D 회전 행렬 (중심 (cx, cy) 기준):
 *   x' = (x - cx) * cos(θ) - (y - cy) * sin(θ) + cx
 *   y' = (y - cy) * cos(θ) + (x - cx) * sin(θ) + cy
 */
public final class CoordinateTransformer {

    private CoordinateTransformer() {}

    /** 중심점 (center) 기준 회전. angleDeg 양수 = 반시계방향 (수학 표준). */
    public static List<Point> rotate(List<Point> points, double angleDeg, Point center) {
        if (points == null) {
            throw new IllegalArgumentException("points 가 null 입니다.");
        }
        if (center == null) {
            throw new IllegalArgumentException("center 가 null 입니다.");
        }
        double rad = Math.toRadians(angleDeg);
        double cos = Math.cos(rad);
        double sin = Math.sin(rad);

        List<Point> result = new ArrayList<>(points.size());
        for (Point p : points) {
            double dx = p.x() - center.x();
            double dy = p.y() - center.y();
            double rx = dx * cos - dy * sin + center.x();
            double ry = dy * cos + dx * sin + center.y();
            result.add(new Point(rx, ry));
        }
        return result;
    }

    /** 원점 기준 비율 스케일. sx, sy 모두 양수 권장 (음수 = 반전). */
    public static List<Point> scale(List<Point> points, double sx, double sy) {
        if (points == null) {
            throw new IllegalArgumentException("points 가 null 입니다.");
        }
        List<Point> result = new ArrayList<>(points.size());
        for (Point p : points) {
            result.add(new Point(p.x() * sx, p.y() * sy));
        }
        return result;
    }

    /** 평행이동. */
    public static List<Point> translate(List<Point> points, double dx, double dy) {
        if (points == null) {
            throw new IllegalArgumentException("points 가 null 입니다.");
        }
        List<Point> result = new ArrayList<>(points.size());
        for (Point p : points) {
            result.add(new Point(p.x() + dx, p.y() + dy));
        }
        return result;
    }
}
