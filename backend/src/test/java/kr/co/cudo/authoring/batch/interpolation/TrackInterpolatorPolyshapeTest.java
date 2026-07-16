package kr.co.cudo.authoring.batch.interpolation;

import kr.co.cudo.authoring.common.util.Point;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * 폴리곤/폴리라인 트랙 보간 단위 테스트 (CVAT polyshape 포팅).
 *
 * <p>BBOX 보간({@link TrackInterpolatorTest})과 동일한 propagate 정책:
 * outside 마커 이후 중단 / 마지막 키프레임 이후 propagate 없음 / 트랙 시작 전 propagate 없음.</p>
 */
class TrackInterpolatorPolyshapeTest {

    private static final double EPS = 1e-6;
    private final TrackInterpolator interpolator = new TrackInterpolator();

    private static Point p(double x, double y) {
        return new Point(x, y);
    }

    @Test
    @DisplayName("Polyshape_빈_키프레임은_빈_결과")
    void emptyKeyframes() {
        Map<Integer, List<Point>> r = interpolator.interpolatePolyshape(List.of(), 100, true);
        assertThat(r).isEmpty();
    }

    @Test
    @DisplayName("Polyshape_단일_키프레임은_그_프레임만")
    void singleKeyframe() {
        List<Point> tri = List.of(p(0, 0), p(10, 0), p(5, 10));
        Map<Integer, List<Point>> r = interpolator.interpolatePolyshape(
                List.of(new PolyKeyframe(3, tri, false)), 100, true);
        assertThat(r).hasSize(1).containsKey(3);
    }

    @Test
    @DisplayName("POLYGON_동일정점_두_키프레임_사이_선형보간")
    void polygonSameVertexCountInterpolated() {
        // frame0 삼각형 → frame2 로 (10,0) 평행이동
        List<Point> t0 = List.of(p(0, 0), p(10, 0), p(5, 10));
        List<Point> t2 = List.of(p(10, 0), p(20, 0), p(15, 10));
        Map<Integer, List<Point>> r = interpolator.interpolatePolyshape(
                List.of(new PolyKeyframe(0, t0, false), new PolyKeyframe(2, t2, false)), 100, true);
        // 0,1,2 = 3개
        assertThat(r).hasSize(3).containsKeys(0, 1, 2);
        // frame1 (t=0.5): 각 정점 +5 이동
        List<Point> mid = r.get(1);
        assertThat(mid).hasSize(3);
        assertThat(mid.get(0).x()).isCloseTo(5, within(EPS));
        assertThat(mid.get(1).x()).isCloseTo(15, within(EPS));
        assertThat(mid.get(2).x()).isCloseTo(10, within(EPS));
        assertThat(mid.get(2).y()).isCloseTo(10, within(EPS));
    }

    @Test
    @DisplayName("POLYGON_정점개수_상이해도_중간프레임_정점수_max로_보간")
    void polygonVariableVertexCount() {
        // frame0 삼각형(3) → frame4 사각형(4). 중간 프레임 정점수 = max(3,4) = 4, 좌표 유한
        List<Point> tri = List.of(p(0, 0), p(10, 0), p(5, 10));
        List<Point> quad = List.of(p(0, 0), p(10, 0), p(10, 10), p(0, 10));
        Map<Integer, List<Point>> r = interpolator.interpolatePolyshape(
                List.of(new PolyKeyframe(0, tri, false), new PolyKeyframe(4, quad, false)), 100, true);
        assertThat(r).containsKeys(0, 1, 2, 3, 4);
        List<Point> mid = r.get(2);
        assertThat(mid).hasSize(4);
        assertThat(mid).allMatch(pt -> Double.isFinite(pt.x()) && Double.isFinite(pt.y()));
    }

    @Test
    @DisplayName("POLYLINE_개곡선_앵커매핑_보간")
    void polylineAnchorInterpolated() {
        List<Point> l0 = List.of(p(0, 0), p(10, 0));
        List<Point> l2 = List.of(p(0, 10), p(10, 10));
        Map<Integer, List<Point>> r = interpolator.interpolatePolyshape(
                List.of(new PolyKeyframe(0, l0, false), new PolyKeyframe(2, l2, false)), 100, false);
        List<Point> mid = r.get(1);
        assertThat(mid).hasSize(2);
        assertThat(mid.get(0).y()).isCloseTo(5, within(EPS));
        assertThat(mid.get(1).y()).isCloseTo(5, within(EPS));
    }

    @Test
    @DisplayName("POLYLINE_방향반전_감지후_정상보간_꼬임없음")
    void polylineReversedDirectionInterpolated() {
        // 두 키프레임의 정점 순서가 반대(반전). 앵커 정렬 후 보간되어 좌표 꼬임 없음.
        List<Point> l0 = List.of(p(0, 0), p(10, 0));
        List<Point> l2reversed = List.of(p(10, 10), p(0, 10));
        Map<Integer, List<Point>> r = interpolator.interpolatePolyshape(
                List.of(new PolyKeyframe(0, l0, false), new PolyKeyframe(2, l2reversed, false)), 100, false);
        List<Point> mid = r.get(1);
        // 반전 보정 → (0,0)↔(0,10), (10,0)↔(10,10) → 중점 (0,5),(10,5). 꼬임(대각선)이면 (5,5) 근처가 됨.
        assertThat(mid.get(0).x()).isCloseTo(0, within(EPS));
        assertThat(mid.get(1).x()).isCloseTo(10, within(EPS));
    }

    @Test
    @DisplayName("POLYGON_정점0개_예외")
    void polygonZeroVertexThrows() {
        assertThatThrownBy(() -> interpolator.interpolatePolyshape(
                List.of(new PolyKeyframe(0, List.of(), false),
                        new PolyKeyframe(2, List.of(), false)), 100, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("POLYGON_정점2개_예외_(면적없음)")
    void polygonTwoVertexThrows() {
        assertThatThrownBy(() -> interpolator.interpolatePolyshape(
                List.of(new PolyKeyframe(0, List.of(p(0, 0), p(10, 0)), false),
                        new PolyKeyframe(2, List.of(p(0, 0), p(10, 0)), false)), 100, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("POLYGON_outside_마커_이후_중단")
    void polygonOutsideStops() {
        List<Point> tri = List.of(p(0, 0), p(10, 0), p(5, 10));
        Map<Integer, List<Point>> r = interpolator.interpolatePolyshape(
                List.of(new PolyKeyframe(0, tri, true), new PolyKeyframe(5, tri, false)), 100, true);
        assertThat(r).isEmpty();
    }

    @Test
    @DisplayName("POLYGON_다음_키프레임이_outside_면_현재만_저장_후_종료")
    void polygonNextOutsideStoresCurrentOnlyAndStops() {
        // BBOX nextOutsideStoresCurrentOnlyAndStops 대칭.
        // 0번 키프레임 저장 → 다음(5)이 outside → 사이 보간 안 함, 종료. 이후(10) 미도달.
        List<Point> t0 = List.of(p(0, 0), p(10, 0), p(5, 10));
        List<Point> t5 = List.of(p(100, 0), p(110, 0), p(105, 10));
        List<Point> t10 = List.of(p(300, 0), p(310, 0), p(305, 10));
        Map<Integer, List<Point>> r = interpolator.interpolatePolyshape(
                List.of(new PolyKeyframe(0, t0, false),
                        new PolyKeyframe(5, t5, true),
                        new PolyKeyframe(10, t10, false)), 100, true);
        assertThat(r).hasSize(1).containsKey(0);
        assertThat(r).doesNotContainKeys(1, 2, 3, 4, 5, 10);
    }

    @Test
    @DisplayName("POLYGON_같은_프레임_중복_키프레임_span0_뒤값우선")
    void polygonDuplicateFrameKeyframeLastWins() {
        // BBOX duplicateFrameKeyframeLastWins 대칭 — span<=0 continue 분기.
        // 같은 frame=5 에 두 키프레임 → 사이 보간 없이 뒤 값이 덮어쓴다.
        List<Point> first = List.of(p(0, 0), p(10, 0), p(5, 10));
        List<Point> second = List.of(p(100, 100), p(200, 100), p(150, 200));
        Map<Integer, List<Point>> r = interpolator.interpolatePolyshape(
                List.of(new PolyKeyframe(5, first, false),
                        new PolyKeyframe(5, second, false)), 100, true);
        assertThat(r).hasSize(1).containsKey(5);
        List<Point> kept = r.get(5);
        assertThat(kept).hasSize(3);
        assertThat(kept.get(0).x()).isCloseTo(100, within(EPS));
        assertThat(kept.get(0).y()).isCloseTo(100, within(EPS));
        assertThat(kept.get(2).x()).isCloseTo(150, within(EPS));
        assertThat(kept.get(2).y()).isCloseTo(200, within(EPS));
    }

    @Test
    @DisplayName("POLYGON_마지막_키프레임_이후_propagate_없음")
    void polygonNoPropagateAfterLast() {
        List<Point> t0 = List.of(p(0, 0), p(10, 0), p(5, 10));
        List<Point> t5 = List.of(p(5, 0), p(15, 0), p(10, 10));
        Map<Integer, List<Point>> r = interpolator.interpolatePolyshape(
                List.of(new PolyKeyframe(0, t0, false), new PolyKeyframe(5, t5, false)), 100, true);
        assertThat(r.keySet()).allMatch(f -> f >= 0 && f <= 5);
        assertThat(r).doesNotContainKeys(6, 50, 99);
    }

    @Test
    @DisplayName("Polyshape_totalFrames_음수면_예외")
    void negativeTotalFrames() {
        assertThatThrownBy(() -> interpolator.interpolatePolyshape(List.of(), -1, true))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
