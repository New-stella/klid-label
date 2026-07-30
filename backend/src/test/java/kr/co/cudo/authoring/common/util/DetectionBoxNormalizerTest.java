package kr.co.cudo.authoring.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * C-ISSUE-41 — 배치·온라인 오토라벨이 공유하는 BBOX 좌표 정규화 규칙 단위 테스트.
 *
 * <p>규칙(요구):
 * <ul>
 *   <li>이미지 경계로 clamp — {@code 0 ≤ x ≤ imgWidth}, {@code 0 ≤ y ≤ imgHeight}.</li>
 *   <li>형식 위반(개수≠4 · null 원소 · NaN/Infinity)만 예외로 거부(호출부 all-or-nothing 400).</li>
 *   <li>clamp 후 퇴화(폭·높이 0 이하) 박스는 예외가 아니라 <b>해당 검출만 스킵</b>({@code Optional.empty()}).</li>
 * </ul>
 */
class DetectionBoxNormalizerTest {

    private static final int[] HD = {1280, 720};

    @Test
    @DisplayName("경계밖_음수좌표는_0으로_clamp되어_반환된다")
    void clampsNegativeToZero() {
        // given — 실측 YOLO 응답(rawSn=26, src=446): x1 이 프레임 왼쪽 경계를 살짝 벗어남.
        List<Double> raw = List.of(-1.5731448368773044, 2.556953126603844, 1261.30, 707.91);

        // when
        Optional<List<Double>> out = DetectionBoxNormalizer.normalizeBbox(raw, HD);

        // then — 전량 거부(400)가 아니라 하한 clamp 후 정상 반환.
        assertThat(out).isPresent();
        assertThat(out.get()).containsExactly(0.0, 2.556953126603844, 1261.30, 707.91);
    }

    @Test
    @DisplayName("y좌표_음수도_0으로_clamp된다")
    void clampsNegativeYToZero() {
        // 실측(rawSn=26, src=449 person): y1 = -0.9496699098489216
        List<Double> raw = List.of(1032.46, -0.9496699098489216, 1279.68, 719.26);

        Optional<List<Double>> out = DetectionBoxNormalizer.normalizeBbox(raw, HD);

        assertThat(out).isPresent();
        assertThat(out.get()).containsExactly(1032.46, 0.0, 1279.68, 719.26);
    }

    @Test
    @DisplayName("이미지_상한_초과좌표는_이미지_경계로_clamp된다")
    void clampsAboveUpperBound() {
        // given — 현재 무검증 통과하던 상한 초과(x2>width, y2>height). 실측 721.30 같은 값이 여기 해당.
        List<Double> raw = List.of(10.0, 10.0, 1300.5, 721.30);

        Optional<List<Double>> out = DetectionBoxNormalizer.normalizeBbox(raw, HD);

        assertThat(out).isPresent();
        assertThat(out.get()).containsExactly(10.0, 10.0, 1280.0, 720.0);
    }

    @Test
    @DisplayName("경계_안쪽_정상좌표는_변형되지_않는다")
    void keepsInBoundsUnchanged() {
        List<Double> raw = List.of(10.0, 20.0, 40.0, 60.0);

        Optional<List<Double>> out = DetectionBoxNormalizer.normalizeBbox(raw, HD);

        assertThat(out).isPresent();
        assertThat(out.get()).containsExactly(10.0, 20.0, 40.0, 60.0);
    }

    @Test
    @DisplayName("이미지_실측_해상도를_모르면_상한없이_음수만_0으로_clamp된다")
    void clampsLowerBoundOnlyWhenBoundsUnknown() {
        // 프레임 이미지 치수 측정 실패(fail-open) — 상한을 강제하면 정상 좌표를 잘라내므로 하한만 적용.
        List<Double> raw = List.of(-5.0, -5.0, 5000.0, 4000.0);

        Optional<List<Double>> out = DetectionBoxNormalizer.normalizeBbox(raw, null);

        assertThat(out).isPresent();
        assertThat(out.get()).containsExactly(0.0, 0.0, 5000.0, 4000.0);
    }

    @Test
    @DisplayName("clamp후_퇴화한_박스는_예외가_아니라_스킵신호를_반환한다")
    void degenerateAfterClampIsSkipped() {
        // 박스 전체가 이미지 왼쪽 바깥 → clamp 후 폭 0. 400 으로 전량 거부하면 같은 프레임의
        // 정상 검출까지 폐기되므로 이 검출만 스킵한다.
        List<Double> raw = List.of(-40.0, 10.0, -5.0, 60.0);

        assertThat(DetectionBoxNormalizer.normalizeBbox(raw, HD)).isEmpty();
    }

    @Test
    @DisplayName("좌표순서가_역전된_박스도_스킵신호를_반환한다")
    void reversedBoxIsSkipped() {
        assertThat(DetectionBoxNormalizer.normalizeBbox(List.of(40.0, 10.0, 40.0, 60.0), HD)).isEmpty();
        assertThat(DetectionBoxNormalizer.normalizeBbox(List.of(10.0, 60.0, 40.0, 10.0), HD)).isEmpty();
    }

    @Test
    @DisplayName("좌표개수가_4개가_아니면_거부한다")
    void rejectsWrongPointCount() {
        assertThatThrownBy(() -> DetectionBoxNormalizer.normalizeBbox(List.of(10.0, 10.0, 40.0), HD))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DetectionBoxNormalizer.normalizeBbox(null, HD))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("NaN_Infinity_좌표는_거부한다_역직렬화_500_회귀방지")
    void rejectsNonFinite() {
        // NaN < 0 은 false 라 음수검사를 통과한다 — isFinite 가드가 없으면 저장 후 좌표 역직렬화에서 500.
        assertThatThrownBy(() -> DetectionBoxNormalizer.normalizeBbox(
                Arrays.asList(10.0, 10.0, Double.NaN, 60.0), HD))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DetectionBoxNormalizer.normalizeBbox(
                Arrays.asList(10.0, 10.0, Double.POSITIVE_INFINITY, 60.0), HD))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DetectionBoxNormalizer.normalizeBbox(
                Arrays.asList(Double.NEGATIVE_INFINITY, 10.0, 40.0, 60.0), HD))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("null_원소가_섞이면_거부한다")
    void rejectsNullElement() {
        assertThatThrownBy(() -> DetectionBoxNormalizer.normalizeBbox(
                Arrays.asList(10.0, null, 40.0, 60.0), HD))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("잘못된_bounds_배열은_상한없음으로_취급한다")
    void invalidBoundsTreatedAsUnknown() {
        // 0/음수/길이불일치 bounds 로 좌표가 전부 0 으로 뭉개지지 않아야 한다(fail-open).
        assertThat(DetectionBoxNormalizer.normalizeBbox(List.of(10.0, 10.0, 40.0, 60.0), new int[]{0, 0}))
                .contains(List.of(10.0, 10.0, 40.0, 60.0));
        assertThat(DetectionBoxNormalizer.normalizeBbox(List.of(10.0, 10.0, 40.0, 60.0), new int[]{1280}))
                .contains(List.of(10.0, 10.0, 40.0, 60.0));
    }
}
