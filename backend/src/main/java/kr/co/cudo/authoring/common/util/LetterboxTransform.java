package kr.co.cudo.authoring.common.util;

/**
 * 레터박스(letterbox/pillarbox) 변환 — <b>종횡비 보존</b> 리스케일의 단일 계산기 (G-1 / E-ISSUE-26).
 *
 * <p>목표 해상도 프레임 안에 원본 종횡비를 유지한 채 최대 크기로 배치하고, 남는 영역은 패딩으로 채운다.
 * 축별 독립 배율({@code scaleX=targetW/srcW}, {@code scaleY=targetH/srcH})로 강제 스케일하면 비-16:9
 * 원본(예: 1080×1920 세로)이 왜곡되므로, <b>균일 배율</b> {@code min(targetW/srcW, targetH/srcH)} 과
 * <b>중앙 정렬 오프셋</b>을 함께 쓴다.
 *
 * <p><b>왜 단일 계산기인가</b>: 픽셀(이미지 리사이즈)과 라벨 좌표(BBOX/POLYGON/세그멘테이션/키포인트)가
 * <b>같은</b> 배율·오프셋을 써야 좌표가 그림 위에 정확히 얹힌다. 두 곳에서 각자 계산하면 반올림 차이만으로도
 * 좌표가 어긋나므로, 정수 반올림까지 포함해 여기서 한 번만 계산한다.
 *
 * <p>순수 계산만 수행한다(파일·상태 없음).
 *
 * @param scale   균일 배율 (양수)
 * @param drawW   실제 그려지는 가로(px)
 * @param drawH   실제 그려지는 세로(px)
 * @param offsetX 좌측 패딩(px) — 라벨 x 좌표에 더한다
 * @param offsetY 상단 패딩(px) — 라벨 y 좌표에 더한다
 */
public record LetterboxTransform(double scale, int drawW, int drawH, int offsetX, int offsetY) {

    /**
     * 원본/목표 치수로 레터박스 변환을 계산한다.
     *
     * @throws IllegalArgumentException 치수가 0 이하일 때
     */
    public static LetterboxTransform of(int srcW, int srcH, int targetW, int targetH) {
        if (srcW <= 0 || srcH <= 0 || targetW <= 0 || targetH <= 0) {
            throw new IllegalArgumentException("치수는 양수여야 합니다");
        }
        double scale = Math.min((double) targetW / srcW, (double) targetH / srcH);
        // 그려지는 크기는 최소 1px 보장(극단 축소 시 0 방지) + 목표 프레임을 넘지 않도록 clamp.
        int drawW = Math.max(1, Math.min(targetW, (int) Math.round(srcW * scale)));
        int drawH = Math.max(1, Math.min(targetH, (int) Math.round(srcH * scale)));
        int offsetX = (targetW - drawW) / 2;
        int offsetY = (targetH - drawH) / 2;
        return new LetterboxTransform(scale, drawW, drawH, offsetX, offsetY);
    }

    /** 원본과 목표의 종횡비가 같아 패딩이 없는가(=단순 배율 변환과 동일). */
    public boolean isExactFit() {
        return offsetX == 0 && offsetY == 0;
    }
}
