package kr.co.cudo.authoring.augment;

import kr.co.cudo.authoring.augment.service.LabelIntegrityCalculator;
import kr.co.cudo.authoring.label.dto.LabelItemDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LabelIntegrityCalculatorTest {

    private final LabelIntegrityCalculator calculator = new LabelIntegrityCalculator();

    private static LabelItemDto bbox(String label, double x1, double y1, double x2, double y2) {
        return new LabelItemDto(null, "BBOX", null, label,
                List.of(List.of(x1, y1), List.of(x2, y2)), null);
    }

    @Test
    @DisplayName("LabelIntegrity_완전_동일_라벨_보존율_100")
    void identicalLabelsReturn100() {
        List<LabelItemDto> original = List.of(
                bbox("car", 10, 10, 50, 50),
                bbox("person", 60, 60, 100, 100)
        );
        List<LabelItemDto> augmented = List.of(
                bbox("car", 10, 10, 50, 50),
                bbox("person", 60, 60, 100, 100)
        );

        BigDecimal score = calculator.calculate(original, augmented);

        assertThat(score).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("LabelIntegrity_완전_다른_라벨_보존율_0")
    void completelyDifferentLabelsReturn0() {
        List<LabelItemDto> original = List.of(
                bbox("car", 10, 10, 50, 50)
        );
        List<LabelItemDto> augmented = List.of(
                // 좌표 IoU 0 (영역 분리), 라벨 텍스트도 다름
                bbox("dog", 200, 200, 300, 300)
        );

        BigDecimal score = calculator.calculate(original, augmented);

        assertThat(score).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("LabelIntegrity_좌표_보존_100_+_카테고리_변경시_가중평균_70")
    void coordPreservedButCategoryChanged() {
        List<LabelItemDto> original = List.of(
                bbox("car", 10, 10, 50, 50)
        );
        List<LabelItemDto> augmented = List.of(
                // 좌표 동일 → IoU 1.0 (보존), 라벨 텍스트만 변경
                bbox("vehicle", 10, 10, 50, 50)
        );

        BigDecimal score = calculator.calculate(original, augmented);

        // 좌표 1.0 * 0.7 + 카테고리 0.0 * 0.3 = 0.7 → 70.00
        assertThat(score).isEqualByComparingTo("70.00");
    }

    @Test
    @DisplayName("LabelIntegrity_원본_또는_증강이_빈_리스트면_0")
    void emptyInputReturns0() {
        assertThat(calculator.calculate(List.of(), List.of(bbox("car", 0, 0, 1, 1))))
                .isEqualByComparingTo("0.00");
        assertThat(calculator.calculate(List.of(bbox("car", 0, 0, 1, 1)), List.of()))
                .isEqualByComparingTo("0.00");
        assertThat(calculator.calculate(null, null))
                .isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("LabelIntegrity_BBOX_미세_이동_IoU_0_9_초과시_보존_간주")
    void slightlyMovedBboxIsPreserved() {
        List<LabelItemDto> original = List.of(
                bbox("car", 0, 0, 100, 100)   // 면적 10000
        );
        // 1픽셀 이동: 교집합 99x99 = 9801 / 합집합 ≈ 10199 → IoU ≈ 0.961
        List<LabelItemDto> augmented = List.of(
                bbox("car", 1, 1, 100, 100)
        );

        BigDecimal score = calculator.calculate(original, augmented);

        // 좌표 IoU > 0.9 → 보존, 라벨 동일 → 100.00
        assertThat(score).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("LabelIntegrity_BBOX_큰_이동_IoU_0_9_미만시_좌표_보존_실패")
    void largelyMovedBboxIsNotPreserved() {
        List<LabelItemDto> original = List.of(
                bbox("car", 0, 0, 100, 100)
        );
        // 50픽셀 이동: 교집합 50x50 = 2500 / 합집합 17500 → IoU ≈ 0.143
        List<LabelItemDto> augmented = List.of(
                bbox("car", 50, 50, 150, 150)
        );

        BigDecimal score = calculator.calculate(original, augmented);

        // 좌표 0.0 * 0.7 + 카테고리 1.0 * 0.3 = 0.3 → 30.00
        assertThat(score).isEqualByComparingTo("30.00");
    }
}
