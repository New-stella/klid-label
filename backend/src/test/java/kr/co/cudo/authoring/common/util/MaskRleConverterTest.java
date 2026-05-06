package kr.co.cudo.authoring.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

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
