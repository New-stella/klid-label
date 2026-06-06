package kr.co.cudo.authoring.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-007 (SFR-08-03) — 경계 세밀함(폴리곤 단순화) 유틸 검증.
 * <p>
 * Douglas-Peucker 알고리즘으로 점을 감소시키되 전체 형태(시작/끝점, 코너)는 보존해야 한다.
 * epsilon(=POLYGON_SIMPLIFY_TOLERANCE) 이 클수록 점이 더 많이 제거된다.
 */
class PolygonSimplifierTest {

    @Test
    @DisplayName("직선상의_중간점은_epsilon이상이면_제거되어_점이_감소")
    void removesCollinearPoints() {
        // given: 한 직선 위에 일렬로 늘어선 점들 (중간점은 불필요)
        List<Point> input = List.of(
                new Point(0, 0),
                new Point(1, 0),
                new Point(2, 0),
                new Point(3, 0),
                new Point(4, 0)
        );

        // when
        List<Point> result = PolygonSimplifier.simplify(input, 1.0);

        // then: 직선이므로 양 끝점만 남아야 한다.
        assertThat(result).hasSize(2);
        assertThat(result.get(0)).isEqualTo(new Point(0, 0));
        assertThat(result.get(result.size() - 1)).isEqualTo(new Point(4, 0));
    }

    @Test
    @DisplayName("형태를_결정하는_코너점은_보존된다")
    void preservesShapeCorners() {
        // given: 사각형 형태 (각 코너는 형태를 결정 → 보존되어야 함)
        List<Point> square = List.of(
                new Point(0, 0),
                new Point(0, 10),
                new Point(10, 10),
                new Point(10, 0),
                new Point(0, 0)
        );

        // when: 작은 tolerance 로 단순화
        List<Point> result = PolygonSimplifier.simplify(square, 1.0);

        // then: 코너가 모두 보존되어 형태가 유지된다.
        assertThat(result).containsAll(List.of(
                new Point(0, 0), new Point(0, 10),
                new Point(10, 10), new Point(10, 0)));
    }

    @Test
    @DisplayName("epsilon이_0이면_원본을_그대로_반환")
    void zeroToleranceKeepsAllPoints() {
        // given: 미세하게 꺾인 점들
        List<Point> input = List.of(
                new Point(0, 0),
                new Point(1, 0.1),
                new Point(2, 0),
                new Point(3, 0.1),
                new Point(4, 0)
        );

        // when
        List<Point> result = PolygonSimplifier.simplify(input, 0.0);

        // then: 단순화 안 함 — 원본 유지.
        assertThat(result).hasSize(input.size());
    }

    @Test
    @DisplayName("큰_epsilon일수록_점이_더_많이_제거된다")
    void largerEpsilonRemovesMorePoints() {
        // given: 살짝 들쭉날쭉한 지그재그
        List<Point> input = List.of(
                new Point(0, 0),
                new Point(1, 2),
                new Point(2, 0),
                new Point(3, 2),
                new Point(4, 0),
                new Point(5, 2),
                new Point(6, 0)
        );

        // when
        List<Point> small = PolygonSimplifier.simplify(input, 0.5);
        List<Point> large = PolygonSimplifier.simplify(input, 5.0);

        // then: 큰 epsilon 이 점을 더 많이 제거.
        assertThat(large.size()).isLessThanOrEqualTo(small.size());
        // 끝점은 항상 보존.
        assertThat(large.get(0)).isEqualTo(new Point(0, 0));
        assertThat(large.get(large.size() - 1)).isEqualTo(new Point(6, 0));
    }

    @Test
    @DisplayName("점이_2개_이하면_그대로_반환")
    void twoOrFewerPointsUnchanged() {
        List<Point> two = List.of(new Point(0, 0), new Point(5, 5));
        assertThat(PolygonSimplifier.simplify(two, 1.0)).isEqualTo(two);

        List<Point> one = List.of(new Point(3, 3));
        assertThat(PolygonSimplifier.simplify(one, 1.0)).isEqualTo(one);
    }

    @Test
    @DisplayName("null또는_빈_입력은_빈리스트_반환")
    void nullOrEmptyReturnsEmpty() {
        assertThat(PolygonSimplifier.simplify(null, 1.0)).isEmpty();
        assertThat(PolygonSimplifier.simplify(List.of(), 1.0)).isEmpty();
    }

    @Test
    @DisplayName("simplifyToMax_4192점_지그재그가_1000점_이하로_감소된다")
    void simplifyToMaxCapsZigzagBelowLimit() {
        // given: 작은 epsilon 으로는 거의 안 줄어드는 4192점 지그재그(SAM2 적재 실측 재현)
        List<Point> dense = new java.util.ArrayList<>();
        for (int i = 0; i < 4192; i++) {
            // 진폭 5px 지그재그 — epsilon=1.0 으로는 대부분 보존되어 점 폭주.
            dense.add(new Point(i, (i % 2 == 0) ? 0 : 5));
        }

        // when
        List<Point> result = PolygonSimplifier.simplifyToMax(dense, 1.0, 1000);

        // then: 상한 1000 이하 + 시작/끝점 보존.
        assertThat(result.size()).isLessThanOrEqualTo(1000);
        assertThat(result.get(0)).isEqualTo(new Point(0, 0));
        assertThat(result.get(result.size() - 1)).isEqualTo(new Point(4191, 5));
    }

    @Test
    @DisplayName("simplifyToMax_상한_이하_입력은_단순화없이_그대로_반환")
    void simplifyToMaxKeepsSmallInput() {
        List<Point> small = List.of(new Point(0, 0), new Point(1, 5), new Point(2, 0), new Point(3, 5));
        assertThat(PolygonSimplifier.simplifyToMax(small, 1.0, 1000)).isEqualTo(small);
    }

    @Test
    @DisplayName("simplifyToMax_균등샘플_폴백도_상한을_보장한다")
    void simplifyToMaxSamplingFallbackHonorsLimit() {
        // given: 모든 점이 형태에 기여(원형 근사) — epsilon 키워도 잘 안 줄어드는 케이스
        List<Point> circle = new java.util.ArrayList<>();
        int n = 5000;
        for (int i = 0; i < n; i++) {
            double t = 2 * Math.PI * i / n;
            circle.add(new Point(1000 + Math.cos(t) * 500, 1000 + Math.sin(t) * 500));
        }

        // when: 작은 상한으로 강제 절삭 유도.
        List<Point> result = PolygonSimplifier.simplifyToMax(circle, 0.01, 200);

        // then: 균등 샘플 폴백을 거쳐도 상한을 넘지 않는다.
        assertThat(result.size()).isLessThanOrEqualTo(200);
    }
}
