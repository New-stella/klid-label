package kr.co.cudo.authoring.batch.interpolation;

import kr.co.cudo.authoring.batch.interpolation.PolyshapeMatcher.PointPair;
import kr.co.cudo.authoring.common.util.Point;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 정점 대응(vertex correspondence) 단위 테스트 — CVAT polyshape 포팅.
 *
 * <p>폐곡선(POLYGON)=최소비용 회전 오프셋 순환 매핑 / 개곡선(POLYLINE)=시작·끝 앵커 고정 매핑.
 * 정점 개수 상이 시 거리기반 대응. 대응쌍 개수 불변식 == max(n, m).</p>
 */
class PolyshapeMatcherTest {

    private static final double EPS = 1e-6;
    private final PolyshapeMatcher matcher = new PolyshapeMatcher();

    private static Point p(double x, double y) {
        return new Point(x, y);
    }

    /** pt 가 pool 중 하나와 (EPS 이내) 일치하는지. */
    private static boolean contains(List<Point> pool, Point pt) {
        return pool.stream().anyMatch(
                q -> Math.abs(q.x() - pt.x()) < EPS && Math.abs(q.y() - pt.y()) < EPS);
    }

    // ─── HIGH #1: 정점 0/1개 fail-fast ───

    @Test
    @DisplayName("정점0개_폴리곤_예외")
    void zeroVertexPolygonRejected() {
        assertThatThrownBy(() -> matcher.match(List.of(), List.of(p(1, 1), p(2, 2), p(3, 3)), true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("정점1개_폴리곤_예외")
    void oneVertexPolygonRejected() {
        assertThatThrownBy(() -> matcher.match(List.of(p(1, 1)), List.of(p(1, 1), p(2, 2), p(3, 3)), true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("정점1개_폴리라인_예외")
    void oneVertexPolylineRejected() {
        assertThatThrownBy(() -> matcher.match(List.of(p(1, 1)), List.of(p(1, 1), p(2, 2)), false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ─── HIGH #2: 정점 2개 POLYGON(면적없음) 거부 ───

    @Test
    @DisplayName("정점2개_폴리곤_면적없음_예외")
    void twoVertexPolygonRejected() {
        assertThatThrownBy(() -> matcher.match(List.of(p(0, 0), p(10, 0)),
                List.of(p(0, 0), p(10, 0), p(5, 5)), true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("정점2개_폴리라인은_유효")
    void twoVertexPolylineAllowed() {
        List<PointPair> pairs = matcher.match(List.of(p(0, 0), p(10, 0)),
                List.of(p(0, 1), p(10, 1)), false);
        assertThat(pairs).hasSize(2);
    }

    // ─── HIGH #3: 극단 상이(300 vs 3) 상한 가드 ───

    @Test
    @DisplayName("정점개수_상한초과_예외")
    void overMaxVerticesRejected() {
        List<Point> huge = new java.util.ArrayList<>();
        for (int i = 0; i < 1001; i++) {
            huge.add(p(i, 0));
        }
        assertThatThrownBy(() -> matcher.match(huge, List.of(p(0, 0), p(1, 1), p(2, 2)), true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("정점개수_300vs3_대응쌍_300개_완료")
    void extremeDifferentCountsMatched() {
        List<Point> big = new java.util.ArrayList<>();
        for (int i = 0; i < 300; i++) {
            double ang = 2 * Math.PI * i / 300;
            big.add(p(Math.cos(ang) * 100, Math.sin(ang) * 100));
        }
        List<Point> small = List.of(p(100, 0), p(-50, 86), p(-50, -86));
        List<PointPair> pairs = matcher.match(big, small, true);
        assertThat(pairs).hasSize(300);
        // 좌표값 검증: from 은 반지름 100 원 위(유한), to 는 반드시 small 3정점 중 하나.
        for (PointPair pp : pairs) {
            assertThat(Double.isFinite(pp.from().x()) && Double.isFinite(pp.from().y())).isTrue();
            assertThat(Math.hypot(pp.from().x(), pp.from().y()))
                    .isCloseTo(100, org.assertj.core.api.Assertions.within(1e-6));
            assertThat(contains(small, pp.to()))
                    .as("to 정점은 small 3개 중 하나여야 함: (%s,%s)", pp.to().x(), pp.to().y())
                    .isTrue();
        }
        // 세 small 정점이 모두 적어도 한 번 대응에 등장(전멸 매핑 아님).
        for (Point s : small) {
            assertThat(pairs.stream().anyMatch(pp -> contains(List.of(s), pp.to()))).isTrue();
        }
    }

    // ─── HIGH #4/#5: 시작=끝 중복점(closed ring, n+1) 정규화 ───

    @Test
    @DisplayName("시작끝중복점_정규화후_보간정확")
    void duplicateClosingVertexNormalized() {
        // [p0,p1,p2,p0] (4pt, 마지막==첫점) → 정규화 후 3pt 삼각형으로 취급
        List<Point> ringWithDup = List.of(p(0, 0), p(10, 0), p(5, 10), p(0, 0));
        List<Point> triangle = List.of(p(0, 0), p(10, 0), p(5, 10));
        List<PointPair> pairs = matcher.match(ringWithDup, triangle, true);
        assertThat(pairs).hasSize(3);
        // 동일 좌표 → 각 대응쌍 from==to (회전 오프셋 0)
        for (PointPair pp : pairs) {
            assertThat(pp.from().x()).isCloseTo(pp.to().x(), org.assertj.core.api.Assertions.within(EPS));
            assertThat(pp.from().y()).isCloseTo(pp.to().y(), org.assertj.core.api.Assertions.within(EPS));
        }
    }

    // ─── HIGH #6: POLYLINE 방향 반전 감지 ───

    @Test
    @DisplayName("폴리라인_방향반전_감지후_앵커정렬")
    void polylineDirectionReversalDetected() {
        // a: (0,0)->(10,0), b: 역방향 (10,1)->(0,1) → 반전 감지 후 시작/끝 앵커 정렬
        List<Point> a = List.of(p(0, 0), p(10, 0));
        List<Point> bReversed = List.of(p(10, 1), p(0, 1));
        List<PointPair> pairs = matcher.match(a, bReversed, false);
        assertThat(pairs).hasSize(2);
        // 반전 보정 후 a[0]=(0,0) ↔ b'(0,1), a[1]=(10,0) ↔ b'(10,1)
        assertThat(pairs.get(0).to().x()).isCloseTo(0, org.assertj.core.api.Assertions.within(EPS));
        assertThat(pairs.get(1).to().x()).isCloseTo(10, org.assertj.core.api.Assertions.within(EPS));
    }

    @Test
    @DisplayName("폴리라인_정방향은_시작끝_앵커_고정")
    void polylineForwardAnchorsFixed() {
        List<Point> a = List.of(p(0, 0), p(5, 0), p(10, 0));
        List<Point> b = List.of(p(0, 2), p(10, 2));
        List<PointPair> pairs = matcher.match(a, b, false);
        // K = max(3,2) = 3
        assertThat(pairs).hasSize(3);
        // 앵커: 시작 a[0]↔b[0], 끝 a[2]↔b[last]
        assertThat(pairs.get(0).to().x()).isCloseTo(0, org.assertj.core.api.Assertions.within(EPS));
        assertThat(pairs.get(2).to().x()).isCloseTo(10, org.assertj.core.api.Assertions.within(EPS));
    }

    // ─── HIGH #7: 대응쌍 개수 불변식 == max(n,m) ───

    @Test
    @DisplayName("대응쌍개수_불변식_n작을때")
    void invariantWhenNLess() {
        List<Point> a = List.of(p(0, 0), p(10, 0), p(5, 10));
        List<Point> b = List.of(p(0, 0), p(10, 0), p(10, 10), p(0, 10), p(5, 15));
        List<PointPair> pairs = matcher.match(a, b, true);
        assertThat(pairs).hasSize(5);
        // 좌표값 검증: 모든 from 은 입력 a 정점, 모든 to 는 입력 b 정점(합성 좌표 유입/유한성 붕괴 없음).
        for (PointPair pp : pairs) {
            assertThat(contains(a, pp.from())).as("from ∈ a").isTrue();
            assertThat(contains(b, pp.to())).as("to ∈ b").isTrue();
        }
    }

    @Test
    @DisplayName("대응쌍개수_불변식_n클때")
    void invariantWhenNGreater() {
        List<Point> a = List.of(p(0, 0), p(10, 0), p(10, 10), p(0, 10), p(5, 15));
        List<Point> b = List.of(p(0, 0), p(10, 0), p(5, 10));
        List<PointPair> pairs = matcher.match(a, b, true);
        assertThat(pairs).hasSize(5);
        for (PointPair pp : pairs) {
            assertThat(contains(a, pp.from())).as("from ∈ a").isTrue();
            assertThat(contains(b, pp.to())).as("to ∈ b").isTrue();
        }
    }

    @Test
    @DisplayName("대응쌍개수_불변식_n동일")
    void invariantWhenEqual() {
        List<Point> a = List.of(p(0, 0), p(10, 0), p(5, 10));
        List<Point> b = List.of(p(1, 1), p(11, 1), p(6, 11));
        assertThat(matcher.match(a, b, true)).hasSize(3);
    }

    // ─── 좌표 유한성 (MED) ───

    @Test
    @DisplayName("NaN좌표_예외")
    void nanCoordinateRejected() {
        assertThatThrownBy(() -> matcher.match(
                List.of(p(0, 0), p(Double.NaN, 0), p(5, 10)),
                List.of(p(0, 0), p(10, 0), p(5, 10)), true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Infinity좌표_예외")
    void infinityCoordinateRejected() {
        assertThatThrownBy(() -> matcher.match(
                List.of(p(0, 0), p(10, 0), p(5, 10)),
                List.of(p(0, 0), p(Double.POSITIVE_INFINITY, 0), p(5, 10)), true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ─── 폐곡선 회전 오프셋 최소비용 ───

    @Test
    @DisplayName("폐곡선_회전된_정점순서_최소비용_정렬")
    void closedRotationOffsetAligned() {
        // 정사각형. b 는 같은 사각형이지만 정점 시작 위치가 1칸 밀림 → 회전 오프셋으로 정렬
        List<Point> a = List.of(p(0, 0), p(10, 0), p(10, 10), p(0, 10));
        List<Point> b = List.of(p(10, 0), p(10, 10), p(0, 10), p(0, 0));
        List<PointPair> pairs = matcher.match(a, b, true);
        assertThat(pairs).hasSize(4);
        // 최적 회전이면 각 대응쌍의 from==to (동일 물리 정점)
        for (PointPair pp : pairs) {
            assertThat(pp.from().x()).isCloseTo(pp.to().x(), org.assertj.core.api.Assertions.within(EPS));
            assertThat(pp.from().y()).isCloseTo(pp.to().y(), org.assertj.core.api.Assertions.within(EPS));
        }
    }

    // ─── 폐곡선 winding 반사(reflection) 대응 ───

    @Test
    @DisplayName("폐곡선_winding반전_반사대응으로_동일정점_무붕괴_매핑")
    void closedWindingReversalReflected() {
        // 동일 삼각형이지만 winding 방향만 반전(정점 나열 순서 반대).
        // 반사 미고려 시 t=0.5 가 선분(0면적)으로 붕괴 → 반사 대응으로 from==to 유지되어야 함.
        List<Point> a = List.of(p(0, 0), p(10, 0), p(5, 10));
        List<Point> bReversed = List.of(p(0, 0), p(5, 10), p(10, 0));
        List<PointPair> pairs = matcher.match(a, bReversed, true);
        assertThat(pairs).hasSize(3);
        // 좌표값 검증: 각 대응쌍이 동일 물리 정점(from==to) → 중간 프레임 형상 붕괴 없음.
        for (PointPair pp : pairs) {
            assertThat(pp.from().x()).isCloseTo(pp.to().x(), org.assertj.core.api.Assertions.within(EPS));
            assertThat(pp.from().y()).isCloseTo(pp.to().y(), org.assertj.core.api.Assertions.within(EPS));
        }
        // 세 정점이 모두 대응 to 에 등장(전멸/중복 붕괴 아님).
        List<Point> tri = List.of(p(0, 0), p(10, 0), p(5, 10));
        for (Point v : tri) {
            assertThat(pairs.stream().anyMatch(pp -> contains(List.of(v), pp.to()))).isTrue();
        }
    }
}
