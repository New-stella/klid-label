package kr.co.cudo.authoring.batch.interpolation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * BBOX 선형 보간 단위 테스트.
 *
 * <p>원본: CVAT 트랙 보간 — {@code docs/analysis/portable-modules/01-track-interpolation.md}</p>
 */
class BboxTest {

    private static final double EPS = 1e-6;

    @Test
    @DisplayName("Bbox_선형보간_t_0이면_a_반환")
    void linearInterpolateAtZeroReturnsA() {
        Bbox a = new Bbox(10, 20, 30, 40);
        Bbox b = new Bbox(100, 200, 300, 400);

        Bbox result = Bbox.linearInterpolate(a, b, 0.0);

        assertThat(result.left()).isCloseTo(10, within(EPS));
        assertThat(result.top()).isCloseTo(20, within(EPS));
        assertThat(result.right()).isCloseTo(30, within(EPS));
        assertThat(result.bottom()).isCloseTo(40, within(EPS));
    }

    @Test
    @DisplayName("Bbox_선형보간_t_1이면_b_반환")
    void linearInterpolateAtOneReturnsB() {
        Bbox a = new Bbox(10, 20, 30, 40);
        Bbox b = new Bbox(100, 200, 300, 400);

        Bbox result = Bbox.linearInterpolate(a, b, 1.0);

        assertThat(result.left()).isCloseTo(100, within(EPS));
        assertThat(result.top()).isCloseTo(200, within(EPS));
        assertThat(result.right()).isCloseTo(300, within(EPS));
        assertThat(result.bottom()).isCloseTo(400, within(EPS));
    }

    @Test
    @DisplayName("Bbox_선형보간_t_0_5이면_중점")
    void linearInterpolateAtHalfReturnsMidpoint() {
        Bbox a = new Bbox(0, 0, 100, 100);
        Bbox b = new Bbox(100, 100, 200, 200);

        Bbox result = Bbox.linearInterpolate(a, b, 0.5);

        assertThat(result.left()).isCloseTo(50, within(EPS));
        assertThat(result.top()).isCloseTo(50, within(EPS));
        assertThat(result.right()).isCloseTo(150, within(EPS));
        assertThat(result.bottom()).isCloseTo(150, within(EPS));
    }

    @Test
    @DisplayName("Bbox_선형보간_null_입력_시_NullPointerException")
    void linearInterpolateRejectsNull() {
        Bbox a = new Bbox(0, 0, 10, 10);

        assertThatThrownBy(() -> Bbox.linearInterpolate(null, a, 0.5))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Bbox.linearInterpolate(a, null, 0.5))
                .isInstanceOf(NullPointerException.class);
    }
}
