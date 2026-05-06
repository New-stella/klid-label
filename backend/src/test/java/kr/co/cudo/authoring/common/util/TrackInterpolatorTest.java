package kr.co.cudo.authoring.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 6 — CVAT 트랙 보간 알고리즘 포팅 테스트 (portable-modules/01).
 * - simple_interpolation 알고리즘 (annotation.py:809)
 * - 회전 최단 경로 (annotation.py:799-807) 의 자바 구현 검증.
 */
class TrackInterpolatorTest {

    private static final double TOL = 0.01;

    @Test
    @DisplayName("TrackInterpolator_RECTANGLE_보간_정확도_0_01_이하")
    void rectangleInterpolationAccuracy() {
        Keyframe k0 = new Keyframe(0, ShapeType.RECTANGLE,
                List.of(new Point(50.0, 50.0), new Point(150.0, 150.0)), 0.0);
        Keyframe k10 = new Keyframe(10, ShapeType.RECTANGLE,
                List.of(new Point(150.0, 150.0), new Point(250.0, 250.0)), 0.0);

        List<Point> at5 = TrackInterpolator.interpolate(List.of(k0, k10), 5, ShapeType.RECTANGLE);

        assertThat(at5).hasSize(2);
        assertThat(at5.get(0).x()).isCloseTo(100.0, org.assertj.core.data.Offset.offset(TOL));
        assertThat(at5.get(0).y()).isCloseTo(100.0, org.assertj.core.data.Offset.offset(TOL));
        assertThat(at5.get(1).x()).isCloseTo(200.0, org.assertj.core.data.Offset.offset(TOL));
        assertThat(at5.get(1).y()).isCloseTo(200.0, org.assertj.core.data.Offset.offset(TOL));
    }

    @Test
    @DisplayName("TrackInterpolator_회전_180도_경계_최단_경로")
    void rotationShortestPathAcrossBoundary() {
        // 179 → -179 의 최단 경로는 +2도 (경계 넘어 시계방향), 358도가 아님.
        // t=0.5에서 결과는 180 또는 -180 근방이어야 한다.
        double interpolated = TrackInterpolator.interpolateRotation(179.0, -179.0, 0.5);

        // 최단 경로를 따라가면 (179 + 1) = 180 또는 (-179 - 1) = -180 ≈ ±180
        double absVal = Math.abs(interpolated);
        assertThat(absVal).isCloseTo(180.0, org.assertj.core.data.Offset.offset(0.5));
    }

    @Test
    @DisplayName("TrackInterpolator_회전_같은_각도시_불변")
    void rotationStaysWhenEqual() {
        double r = TrackInterpolator.interpolateRotation(45.0, 45.0, 0.7);
        assertThat(r).isCloseTo(45.0, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    @DisplayName("TrackInterpolator_키프레임_정확히_매칭시_동일_좌표")
    void exactKeyframeMatch() {
        Keyframe k0 = new Keyframe(0, ShapeType.RECTANGLE,
                List.of(new Point(0.0, 0.0), new Point(100.0, 100.0)), 0.0);
        Keyframe k10 = new Keyframe(10, ShapeType.RECTANGLE,
                List.of(new Point(100.0, 100.0), new Point(200.0, 200.0)), 90.0);

        List<Point> at0 = TrackInterpolator.interpolate(List.of(k0, k10), 0, ShapeType.RECTANGLE);
        List<Point> at10 = TrackInterpolator.interpolate(List.of(k0, k10), 10, ShapeType.RECTANGLE);

        assertThat(at0.get(0).x()).isCloseTo(0.0, org.assertj.core.data.Offset.offset(TOL));
        assertThat(at10.get(1).y()).isCloseTo(200.0, org.assertj.core.data.Offset.offset(TOL));
    }

    @Test
    @DisplayName("TrackInterpolator_타겟_프레임이_트랙_범위_밖이면_경계_propagate")
    void outsideRangePropagates() {
        Keyframe k0 = new Keyframe(5, ShapeType.RECTANGLE,
                List.of(new Point(10.0, 10.0), new Point(50.0, 50.0)), 0.0);
        Keyframe k10 = new Keyframe(15, ShapeType.RECTANGLE,
                List.of(new Point(20.0, 20.0), new Point(60.0, 60.0)), 0.0);

        // 트랙 시작 이전 = 첫 키프레임 좌표 그대로 propagate
        List<Point> before = TrackInterpolator.interpolate(List.of(k0, k10), 0, ShapeType.RECTANGLE);
        assertThat(before.get(0).x()).isEqualTo(10.0);

        // 트랙 종료 이후 = 마지막 키프레임 좌표 그대로 propagate
        List<Point> after = TrackInterpolator.interpolate(List.of(k0, k10), 100, ShapeType.RECTANGLE);
        assertThat(after.get(1).y()).isEqualTo(60.0);
    }

    @Test
    @DisplayName("TrackInterpolator_POLYGON_미지원_NotImplemented")
    void polygonNotImplementedYet() {
        Keyframe k0 = new Keyframe(0, ShapeType.POLYGON,
                List.of(new Point(0.0, 0.0), new Point(10.0, 0.0), new Point(5.0, 10.0)), 0.0);
        Keyframe k10 = new Keyframe(10, ShapeType.POLYGON,
                List.of(new Point(0.0, 0.0), new Point(10.0, 0.0), new Point(5.0, 10.0)), 0.0);

        assertThatThrownBy(() -> TrackInterpolator.interpolate(List.of(k0, k10), 5, ShapeType.POLYGON))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("TrackInterpolator_빈_키프레임_입력시_예외")
    void emptyKeyframesRejected() {
        assertThatThrownBy(() -> TrackInterpolator.interpolate(List.of(), 5, ShapeType.RECTANGLE))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
