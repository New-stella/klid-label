package kr.co.cudo.authoring.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 6 — CVAT MASK ↔ RLE 변환 포팅 테스트 (portable-modules/02).
 * - CVAT 자체 RLE 형식: 첫 run 은 항상 0(off) 픽셀 카운트.
 * - 1000 회 random mask 양방향 변환 무결성 검증.
 */
class MaskRleConverterTest {

    @Test
    @DisplayName("MaskRleConverter_RLE_양방향_변환_무결성_1000회")
    void rleRoundtripIntegrity1000() {
        Random rnd = new Random(42L);
        for (int trial = 0; trial < 1000; trial++) {
            int h = 1 + rnd.nextInt(20);
            int w = 1 + rnd.nextInt(20);
            boolean[][] original = new boolean[h][w];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    original[y][x] = rnd.nextBoolean();
                }
            }

            int[] rle = MaskRleConverter.maskToRle(original);
            boolean[][] decoded = MaskRleConverter.rleToMask(rle, w, h);

            assertThat(decoded).hasDimensions(h, w);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    assertThat(decoded[y][x])
                            .as("trial=%d (y=%d, x=%d)", trial, y, x)
                            .isEqualTo(original[y][x]);
                }
            }
        }
    }

    @Test
    @DisplayName("MaskRleConverter_빈_mask_RLE_정상_처리")
    void emptyMaskHandled() {
        boolean[][] empty = new boolean[5][5]; // 모두 false (off)
        int[] rle = MaskRleConverter.maskToRle(empty);

        assertThat(rle).isNotNull();
        // 모두 0(off) → CVAT RLE 는 [25] (한 run = 25개의 off)
        assertThat(rle).containsExactly(25);

        boolean[][] decoded = MaskRleConverter.rleToMask(rle, 5, 5);
        assertThat(decoded).hasDimensions(5, 5);
        for (int y = 0; y < 5; y++) {
            for (int x = 0; x < 5; x++) {
                assertThat(decoded[y][x]).isFalse();
            }
        }
    }

    @Test
    @DisplayName("MaskRleConverter_첫_픽셀이_on이면_맨_앞에_0_run_삽입")
    void leadingOnInsertsZeroRun() {
        boolean[][] mask = new boolean[1][3];
        mask[0][0] = true;
        mask[0][1] = true;
        mask[0][2] = false;

        int[] rle = MaskRleConverter.maskToRle(mask);

        // CVAT 규칙: 항상 0에서 시작 (off-run 먼저). on 시작이면 0 길이 0-run 삽입.
        // [0(off), 2(on), 1(off)]
        assertThat(rle).containsExactly(0, 2, 1);
    }

    @Test
    @DisplayName("MaskRleConverter_전체_on_mask_RLE_정상_처리")
    void allOnMaskHandled() {
        boolean[][] mask = new boolean[2][3];
        for (int y = 0; y < 2; y++) {
            for (int x = 0; x < 3; x++) {
                mask[y][x] = true;
            }
        }
        int[] rle = MaskRleConverter.maskToRle(mask);
        // [0(off zero-length), 6(on)]
        assertThat(rle).containsExactly(0, 6);

        boolean[][] decoded = MaskRleConverter.rleToMask(rle, 3, 2);
        for (int y = 0; y < 2; y++) {
            for (int x = 0; x < 3; x++) {
                assertThat(decoded[y][x]).isTrue();
            }
        }
    }

    @Test
    @DisplayName("MaskRleConverter_rleToMask_RLE가_null이면_예외")
    void rleToMaskRejectsNullRle() {
        // given/when/then — null RLE 는 역직렬화 진입 자체를 거부한다(NPE 대신 명시 예외).
        assertThatThrownBy(() -> MaskRleConverter.rleToMask(null, 5, 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("RLE 가 null");
    }

    @Test
    @DisplayName("MaskRleConverter_rleToMask_DoS_상한_초과시_예외")
    void rleToMaskRejectsExcessivePixels() {
        // given — CWE-770: width*height 가 MAX_PIXELS(1,000,000) 를 넘으면 mask 할당 전에 거부.
        int width = 1001;
        int height = 1001; // 1,002,001 픽셀

        // when/then — 할당(boolean[1001][1001])이 일어나기 전에 예외가 나야 한다.
        assertThatThrownBy(() -> MaskRleConverter.rleToMask(new int[]{0}, width, height))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("최대 허용치 초과")
                .hasMessageContaining(String.valueOf(MaskRleConverter.MAX_PIXELS));
    }

    @Test
    @DisplayName("MaskRleConverter_rleToMask_width가_0이하면_예외")
    void rleToMaskRejectsNonPositiveWidth() {
        assertThatThrownBy(() -> MaskRleConverter.rleToMask(new int[]{0}, 0, 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("양수여야 합니다");
        assertThatThrownBy(() -> MaskRleConverter.rleToMask(new int[]{0}, -3, 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("양수여야 합니다");
    }

    @Test
    @DisplayName("MaskRleConverter_rleToMask_height가_0이하면_예외")
    void rleToMaskRejectsNonPositiveHeight() {
        assertThatThrownBy(() -> MaskRleConverter.rleToMask(new int[]{0}, 5, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("양수여야 합니다");
        assertThatThrownBy(() -> MaskRleConverter.rleToMask(new int[]{0}, 5, -3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("양수여야 합니다");
    }

    @Test
    @DisplayName("MaskRleConverter_rleToMask_DoS_상한_검사가_양수_검사보다_먼저_수행된다")
    void rleToMaskChecksPixelLimitBeforePositiveDimensions() {
        // given — 두 가드가 동시에 걸리는 입력: 음수 × 음수라 곱은 양수(4,000,000 > MAX_PIXELS)이고
        //         width/height 는 양수가 아니다. 가드 <선언 순서>(상한 → 양수)를 고정한다.
        int width = -2000;
        int height = -2000;

        // when/then — 상한 검사가 먼저이므로 "최대 허용치 초과" 메시지여야 한다.
        //             순서를 뒤집으면(양수 검사 선행) 메시지가 "양수여야 합니다" 로 바뀌어 실패한다.
        assertThatThrownBy(() -> MaskRleConverter.rleToMask(new int[]{0}, width, height))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("최대 허용치 초과")
                .hasMessageNotContaining("양수여야 합니다");
    }

    @Test
    @DisplayName("MaskRleConverter_rleToMask_RLE_길이합이_총_픽셀수_초과시_예외")
    void rleToMaskRejectsRunLengthOverflow() {
        // given — total = 5*3 = 15 인데 run 합계는 20 → idx 가 15 에 도달하는 순간 거부.
        int[] rle = {10, 10};

        // when/then
        assertThatThrownBy(() -> MaskRleConverter.rleToMask(rle, 5, 3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("RLE 길이 합이 15 초과");
    }

    @Test
    @DisplayName("MaskRleConverter_rleToMask_RLE_길이합이_총_픽셀수와_같으면_정상")
    void rleToMaskAcceptsExactRunLength() {
        // given — 경계값: run 합계 == total(15) 이면 예외 없이 복원된다(off-by-one 회귀 방어).
        int[] rle = {10, 5};

        // when
        boolean[][] mask = MaskRleConverter.rleToMask(rle, 5, 3);

        // then — 앞 10 픽셀 off, 뒤 5 픽셀 on.
        assertThat(mask).hasDimensions(3, 5);
        assertThat(mask[0][0]).isFalse();
        assertThat(mask[1][4]).isFalse();
        assertThat(mask[2][0]).isTrue();
        assertThat(mask[2][4]).isTrue();
    }

    @Test
    @DisplayName("MaskRleConverter_DoS_최대_크기_1000x1000_초과시_예외")
    void rejectsExcessivelyLargeMask() {
        // CWE-770 DoS — 무제한 변환 차단.
        // MAX_PIXELS 는 1_000_000 이므로 1001 x 1001 (= 1_002_001 픽셀) 이면 초과 트리거.
        // 큰 단일 행(예: [10][1024*1024+1]) 대신 1001x1001 사용 — 약 1MB 메모리만 사용해 OOM 위험 회피.
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> {
            boolean[][] huge = new boolean[1001][1001];
            MaskRleConverter.maskToRle(huge);
        });
    }
}
