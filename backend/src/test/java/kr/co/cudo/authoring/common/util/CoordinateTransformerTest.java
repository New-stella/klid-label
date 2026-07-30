package kr.co.cudo.authoring.common.util;

import org.assertj.core.data.Offset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 6 — CVAT 좌표 변환 / 회전 포팅 테스트 (portable-modules/06).
 * - 표준 2D 회전 행렬 (중심 (cx,cy) 기준): shared.ts:159-174
 * - 역변환 무결성: rotate(rotate(p, +θ), -θ) ≈ p (1e-9 허용).
 */
class CoordinateTransformerTest {

    private static final Offset<Double> EPS = Offset.offset(1e-9);

    @Test
    @DisplayName("CoordinateTransformer_45도_회전_역변환_원점_복귀")
    void rotateRoundtripIdentity() {
        Point center = new Point(50.0, 50.0);
        List<Point> original = List.of(new Point(0.0, 0.0), new Point(100.0, 0.0), new Point(50.0, 100.0));

        List<Point> rotated = CoordinateTransformer.rotate(original, 45.0, center);
        List<Point> back = CoordinateTransformer.rotate(rotated, -45.0, center);

        assertThat(back).hasSize(3);
        for (int i = 0; i < 3; i++) {
            assertThat(back.get(i).x()).isCloseTo(original.get(i).x(), EPS);
            assertThat(back.get(i).y()).isCloseTo(original.get(i).y(), EPS);
        }
    }

    @Test
    @DisplayName("CoordinateTransformer_스케일_역변환_원점_복귀")
    void scaleRoundtripIdentity() {
        List<Point> original = List.of(new Point(10.0, 20.0), new Point(30.0, 40.0));
        List<Point> scaled = CoordinateTransformer.scale(original, 2.5, 3.0);
        List<Point> back = CoordinateTransformer.scale(scaled, 1.0 / 2.5, 1.0 / 3.0);

        for (int i = 0; i < 2; i++) {
            assertThat(back.get(i).x()).isCloseTo(original.get(i).x(), EPS);
            assertThat(back.get(i).y()).isCloseTo(original.get(i).y(), EPS);
        }
    }

    @Test
    @DisplayName("CoordinateTransformer_translate_역변환_원점_복귀")
    void translateRoundtripIdentity() {
        List<Point> original = List.of(new Point(5.0, 7.0), new Point(15.0, 17.0));
        List<Point> moved = CoordinateTransformer.translate(original, 100.0, -50.0);
        List<Point> back = CoordinateTransformer.translate(moved, -100.0, 50.0);

        for (int i = 0; i < 2; i++) {
            assertThat(back.get(i).x()).isCloseTo(original.get(i).x(), EPS);
            assertThat(back.get(i).y()).isCloseTo(original.get(i).y(), EPS);
        }
    }

    @Test
    @DisplayName("CoordinateTransformer_90도_회전_좌표_검증")
    void rotate90Verified() {
        // (10, 0) 을 원점 기준 +90도 회전하면 (0, 10).
        List<Point> rotated = CoordinateTransformer.rotate(
                List.of(new Point(10.0, 0.0)), 90.0, new Point(0.0, 0.0));
        assertThat(rotated.get(0).x()).isCloseTo(0.0, Offset.offset(1e-9));
        assertThat(rotated.get(0).y()).isCloseTo(10.0, Offset.offset(1e-9));
    }

    @Test
    @DisplayName("CoordinateTransformer_빈_리스트_입력시_빈_리스트_반환")
    void emptyInputHandled() {
        List<Point> result = CoordinateTransformer.rotate(List.of(), 45.0, new Point(0.0, 0.0));
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("CoordinateTransformer_rotate_points가_null이면_예외")
    void rotateRejectsNullPoints() {
        // given/when/then — NPE 대신 입력 검증 예외로 거부한다.
        assertThatThrownBy(() -> CoordinateTransformer.rotate(null, 45.0, new Point(0.0, 0.0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("points 가 null");
    }

    @Test
    @DisplayName("CoordinateTransformer_rotate_center가_null이면_예외")
    void rotateRejectsNullCenter() {
        assertThatThrownBy(() -> CoordinateTransformer.rotate(List.of(new Point(1.0, 2.0)), 45.0, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("center 가 null");
    }

    @Test
    @DisplayName("CoordinateTransformer_rotate_두_인자가_모두_null이면_points_가드가_먼저_발동한다")
    void rotateChecksPointsGuardBeforeCenterGuard() {
        // given/when/then — 가드 <선언 순서>(points → center) 고정. 순서를 뒤집으면 실패한다.
        assertThatThrownBy(() -> CoordinateTransformer.rotate(null, 45.0, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("points 가 null")
                .hasMessageNotContaining("center 가 null");
    }

    @Test
    @DisplayName("CoordinateTransformer_scale_points가_null이면_예외")
    void scaleRejectsNullPoints() {
        assertThatThrownBy(() -> CoordinateTransformer.scale(null, 2.0, 2.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("points 가 null");
    }

    @Test
    @DisplayName("CoordinateTransformer_translate_points가_null이면_예외")
    void translateRejectsNullPoints() {
        assertThatThrownBy(() -> CoordinateTransformer.translate(null, 1.0, 1.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("points 가 null");
    }
}
