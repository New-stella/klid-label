package kr.co.cudo.authoring.common.util;

import java.util.ArrayList;
import java.util.List;

/**
 * CVAT 트랙 보간 알고리즘 자바 포팅 (portable-modules/01).
 *
 * 원본: cvat/apps/dataset_manager/annotation.py
 *  - simple_interpolation (annotation.py:809) — RECTANGLE/ELLIPSE/CUBOID/SKELETON
 *  - find_angle_diff (annotation.py:799-807) — 회전 최단 경로
 *
 * 본 Phase 6 에서 지원:
 *  - RECTANGLE / ELLIPSE / CUBOID 단순 선형 보간
 *  - 회전 최단 경로 (180도 경계 처리 — `(angle+180)%360-180`)
 *
 * TODO (V1.7+ 후속):
 *  - POLYGON / POLYLINE: polyshape_interpolation (annotation.py:849-1066)
 *  - MASK / SKELETON elements 매칭
 */
public final class TrackInterpolator {

    private TrackInterpolator() {}

    /**
     * 임의 프레임의 보간 좌표 산출.
     *
     * @param keyframes 키프레임 시퀀스 (frameNo 오름차순 정렬 가정)
     * @param targetFrame 보간 대상 프레임 번호
     * @param shapeType   shape 타입
     * @return 해당 프레임의 추정 좌표
     */
    public static List<Point> interpolate(List<Keyframe> keyframes, int targetFrame, ShapeType shapeType) {
        if (keyframes == null || keyframes.isEmpty()) {
            throw new IllegalArgumentException("키프레임이 비어있습니다.");
        }
        if (shapeType == ShapeType.POLYGON) {
            // POLYGON 보간은 후속 Phase. CVAT polyshape_interpolation 필요.
            throw new UnsupportedOperationException("POLYGON 보간은 본 Phase 에서 미지원 (V1.7+ TODO).");
        }
        if (shapeType == ShapeType.MASK || shapeType == ShapeType.SKELETON) {
            throw new UnsupportedOperationException(shapeType + " 보간은 본 Phase 에서 미지원.");
        }

        // 1. 트랙 시작 이전 → 첫 키프레임 propagate.
        Keyframe first = keyframes.get(0);
        if (targetFrame <= first.frameNo()) {
            return new ArrayList<>(first.points());
        }
        // 2. 트랙 종료 이후 → 마지막 키프레임 propagate.
        Keyframe last = keyframes.get(keyframes.size() - 1);
        if (targetFrame >= last.frameNo()) {
            return new ArrayList<>(last.points());
        }

        // 3. 키프레임 사이 구간 탐색 후 simple_interpolation.
        for (int i = 0; i < keyframes.size() - 1; i++) {
            Keyframe k0 = keyframes.get(i);
            Keyframe k1 = keyframes.get(i + 1);
            if (targetFrame >= k0.frameNo() && targetFrame <= k1.frameNo()) {
                return simpleInterpolation(k0, k1, targetFrame);
            }
        }
        // 도달 불가 (위 propagate 분기에서 모두 처리). 안전 가드:
        return new ArrayList<>(last.points());
    }

    /**
     * simple_interpolation (annotation.py:809) 자바 구현.
     * 두 키프레임의 동일 인덱스 좌표를 선형 보간.
     */
    private static List<Point> simpleInterpolation(Keyframe k0, Keyframe k1, int targetFrame) {
        if (k0.points().size() != k1.points().size()) {
            throw new IllegalStateException("좌표 개수가 다른 두 키프레임은 simple 보간 불가: "
                    + k0.points().size() + " vs " + k1.points().size());
        }
        int distance = k1.frameNo() - k0.frameNo();
        if (distance == 0) {
            return new ArrayList<>(k0.points());
        }
        double offset = (double) (targetFrame - k0.frameNo()) / distance;

        List<Point> result = new ArrayList<>(k0.points().size());
        for (int i = 0; i < k0.points().size(); i++) {
            Point p0 = k0.points().get(i);
            Point p1 = k1.points().get(i);
            double x = p0.x() + (p1.x() - p0.x()) * offset;
            double y = p0.y() + (p1.y() - p0.y()) * offset;
            result.add(new Point(x, y));
        }
        return result;
    }

    /**
     * 회전각 선형 보간 (최단 경로).
     * 원본: find_angle_diff (annotation.py:799-807)
     *
     * (angle_diff + 180) % 360 - 180 으로 [-180, 180] 정규화.
     *
     * @param from 시작 각도 (degree)
     * @param to   종료 각도 (degree)
     * @param t    0.0 ~ 1.0
     * @return 보간된 각도
     */
    public static double interpolateRotation(double from, double to, double t) {
        double rawDiff = to - from;
        double angleDiff = ((rawDiff + 180.0) % 360.0 + 360.0) % 360.0 - 180.0;
        return from + angleDiff * t;
    }
}
