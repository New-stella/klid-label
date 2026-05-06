package kr.co.cudo.authoring.common.util;

/**
 * CVAT MASK ↔ RLE 변환 자바 포팅 (portable-modules/02).
 *
 * 원본:
 *  - cvat/apps/dataset_manager/formats/transformations.py:104-120 (rle())
 *  - cvat-canvas/src/typescript/shared.ts:408-426 (imageDataToRLE)
 *
 * CVAT 자체 RLE 형식:
 *  - 첫 run 은 항상 0(off) 픽셀 카운트.
 *  - 첫 픽셀이 on 이면 맨 앞에 길이 0 인 off-run 삽입.
 *  - 형식: [run0, run1, run2, ..., runN]   (BBOX 4개 값은 호출부에서 별도 부착)
 *
 * 보안:
 *  - CWE-770 DoS 방어: 픽셀 총 수 1,000,000 (1M) 제한.
 *    SAM2 / Brush 결과의 평균 mask 크기는 수만 픽셀 → 1M 면 충분히 큼.
 */
public final class MaskRleConverter {

    /** CWE-770 DoS 방어 — 단일 mask 최대 픽셀 수. */
    public static final int MAX_PIXELS = 1_000_000;

    private MaskRleConverter() {}

    /**
     * 2D mask → CVAT RLE.
     * mask[y][x] (height × width).
     */
    public static int[] maskToRle(boolean[][] mask) {
        if (mask == null || mask.length == 0) {
            return new int[0];
        }
        int h = mask.length;
        int w = mask[0].length;
        long total = (long) h * w;
        if (total > MAX_PIXELS) {
            throw new IllegalArgumentException("MASK 크기가 최대 허용치 초과 ("
                    + MAX_PIXELS + " 픽셀): " + total);
        }

        // flatten row-major
        boolean[] flat = new boolean[(int) total];
        for (int y = 0; y < h; y++) {
            if (mask[y].length != w) {
                throw new IllegalArgumentException("MASK 가 직사각형이 아닙니다: row " + y + " width=" + mask[y].length);
            }
            System.arraycopy(mask[y], 0, flat, y * w, w);
        }

        // CVAT 규칙: 첫 픽셀이 on 이면 맨 앞에 0-length off-run 삽입.
        java.util.ArrayList<Integer> rle = new java.util.ArrayList<>();
        if (flat[0]) {
            rle.add(0);
        }
        boolean cur = false; // 시작 가정 (off)
        if (flat[0]) {
            cur = true; // 첫 0-run 다음은 on
        }
        int count = 0;
        for (int i = 0; i < flat.length; i++) {
            boolean v = flat[i];
            if (v == cur) {
                count++;
            } else {
                rle.add(count);
                cur = v;
                count = 1;
            }
        }
        rle.add(count);

        int[] result = new int[rle.size()];
        for (int i = 0; i < rle.size(); i++) {
            result[i] = rle.get(i);
        }
        return result;
    }

    /**
     * CVAT RLE → 2D mask.
     * width / height 는 호출부에서 BBOX 로부터 산출하여 전달.
     */
    public static boolean[][] rleToMask(int[] rle, int width, int height) {
        if (rle == null) {
            throw new IllegalArgumentException("RLE 가 null 입니다.");
        }
        long total = (long) width * height;
        if (total > MAX_PIXELS) {
            throw new IllegalArgumentException("MASK 크기가 최대 허용치 초과 ("
                    + MAX_PIXELS + " 픽셀): " + total);
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("width/height 는 양수여야 합니다: " + width + "x" + height);
        }

        boolean[][] mask = new boolean[height][width];
        boolean cur = false; // CVAT 규칙: 항상 off 에서 시작
        int idx = 0;
        for (int run : rle) {
            for (int k = 0; k < run; k++) {
                if (idx >= total) {
                    throw new IllegalArgumentException("RLE 길이 합이 " + total + " 초과");
                }
                int y = idx / width;
                int x = idx % width;
                mask[y][x] = cur;
                idx++;
            }
            cur = !cur;
        }
        return mask;
    }
}
