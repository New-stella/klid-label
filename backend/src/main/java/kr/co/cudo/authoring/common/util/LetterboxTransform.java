package kr.co.cudo.authoring.common.util;

/**
 * 해상도 파생 리스케일의 <b>단일 계산기</b> — 종횡비 보존 + 가변 캔버스 (@design ADR-018).
 *
 * <h3>규칙 — 프리셋은 고정 캔버스가 아니라 크기 상한이다</h3>
 * <ol>
 *   <li>원본의 <b>짧은 변</b>을 프리셋의 짧은 값(1080/720/480)에 맞추는 배율을 구한다.</li>
 *   <li>그 배율로 계산한 <b>긴 변</b>이 프리셋의 긴 값(1920/1280/854)을 넘으면, 그때만 긴 변이 상한에
 *       맞도록 배율을 낮춘다.</li>
 *   <li>산출 크기는 그 배율로 계산된 실제 크기이며 <b>남는 영역을 채우는 패딩이 없다</b>
 *       ({@code offsetX == offsetY == 0}).</li>
 *   <li>가로·세로에 <b>같은 배율</b>을 적용한다(종횡비 보존).</li>
 * </ol>
 *
 * <p><b>구현 형태</b>: 위 1~2 는 짧은변/긴변으로 <b>정렬한 뒤</b> 두 배율 중 작은 쪽을 취하는 것과
 * 수학적으로 같으므로 분기 없이 {@code min(shortTarget/srcShort, longTarget/srcLong)} 로 계산한다.
 * ⚠ 이것은 박스 피팅 {@code min(targetW/srcW, targetH/srcH)} 과 <b>다르다</b> — 박스 피팅은 축을
 * 원본 방향에 맞추지 않아 세로 영상을 405×720 처럼 과소 산출한다(기각된 안). 두 식이 갈리는 지점은
 * 세로·극단 종횡비이며, 가로 영상에서는 결과가 같다.
 *
 * <p><b>왜 단일 계산기인가</b>: 픽셀(이미지 리사이즈)과 라벨 좌표(BBOX/POLYGON/세그멘테이션/키포인트)가
 * <b>같은</b> 배율을 써야 좌표가 그림 위에 정확히 얹힌다. 두 곳에서 각자 계산하면 반올림 차이만으로도
 * 좌표가 어긋나므로, 정수 반올림까지 포함해 여기서 한 번만 계산한다.
 *
 * <p><b>이력</b>: 축별 독립 배율({@code scaleX=targetW/srcW}, {@code scaleY=targetH/srcH})로 강제
 * 스케일해 비-16:9 원본을 <b>왜곡</b>시키던 방식 → 종횡비 보존 + 검정 레터박스 → 종횡비 보존 +
 * 가변 캔버스(현행, 패딩 없음). 왜곡 방식으로 되돌리지 말 것.
 *
 * <p>순수 계산만 수행한다(파일·상태 없음).
 *
 * @param scale   균일 배율 (양수) — 가로·세로에 동일 적용
 * @param drawW   산출 캔버스 가로(px) = 실제 그려지는 가로
 * @param drawH   산출 캔버스 세로(px) = 실제 그려지는 세로
 * @param offsetX 좌측 오프셋(px) — 패딩이 없으므로 <b>항상 0</b>
 * @param offsetY 상단 오프셋(px) — 패딩이 없으므로 <b>항상 0</b>
 */
public record LetterboxTransform(double scale, int drawW, int drawH, int offsetX, int offsetY) {

    /**
     * 원본 치수와 프리셋 <b>상한</b>으로 리스케일 변환을 계산한다.
     *
     * @param srcW    원본 가로(px)
     * @param srcH    원본 세로(px)
     * @param targetW 프리셋 가로 수치 — 고정 캔버스가 아니라 상한
     * @param targetH 프리셋 세로 수치 — 고정 캔버스가 아니라 상한
     * @throws IllegalArgumentException 치수가 0 이하일 때
     */
    public static LetterboxTransform of(int srcW, int srcH, int targetW, int targetH) {
        if (srcW <= 0 || srcH <= 0 || targetW <= 0 || targetH <= 0) {
            throw new IllegalArgumentException("치수는 양수여야 합니다");
        }
        int shortTarget = Math.min(targetW, targetH);
        int longTarget = Math.max(targetW, targetH);
        int srcShort = Math.min(srcW, srcH);
        int srcLong = Math.max(srcW, srcH);
        // 짧은 변 기준 배율 + 긴 변 상한 클램프. 둘 중 작은 쪽을 취하면 분기 없이 같은 결과가 된다.
        double scale = Math.min((double) shortTarget / srcShort, (double) longTarget / srcLong);
        // 산출 크기는 최소 1px 보장(극단 축소 시 0 방지). 프리셋 수치로 clamp 하지 않는다 — 캔버스가 가변이다.
        int drawW = Math.max(1, (int) Math.round(srcW * scale));
        int drawH = Math.max(1, (int) Math.round(srcH * scale));
        return new LetterboxTransform(scale, drawW, drawH, 0, 0);
    }

    /**
     * 패딩이 없는가 — 가변 캔버스 전환 이후 <b>항상 {@code true}</b> 다.
     *
     * <p>레터박스(고정 캔버스) 시절에는 "원본과 목표의 종횡비가 같아 패딩이 없는가"를 뜻했다. 패딩 자체가
     * 폐지되어 그 구분이 소멸했으므로 이 판정은 불변식 회귀 가드로만 의미를 갖는다(오프셋이 다시 생기면
     * 좌표 변환이 곱셈만이라는 확정 사양이 깨진다).
     */
    public boolean isExactFit() {
        return offsetX == 0 && offsetY == 0;
    }
}
