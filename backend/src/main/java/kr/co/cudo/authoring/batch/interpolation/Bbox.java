package kr.co.cudo.authoring.batch.interpolation;

import java.util.Objects;

/**
 * 축 정렬 바운딩 박스 (axis-aligned bounding box) 값 객체.
 *
 * <p>좌표는 픽셀 단위 실수. (left, top) 좌상단, (right, bottom) 우하단.
 * 회전/스큐 비지원 (CVAT 의 단순 BBOX 와 동일).</p>
 *
 * <p>원본: CVAT 트랙 보간 — {@code docs/analysis/portable-modules/01-track-interpolation.md}</p>
 *
 * @param left   좌상단 x
 * @param top    좌상단 y
 * @param right  우하단 x
 * @param bottom 우하단 y
 */
public record Bbox(double left, double top, double right, double bottom) {

    /**
     * 두 박스 사이의 선형 보간.
     *
     * <p>각 꼭짓점 좌표를 독립적으로 선형 보간한다.
     * {@code t=0} 이면 {@code a}, {@code t=1} 이면 {@code b}, {@code t=0.5} 이면 중점.</p>
     *
     * @param a 시작 박스 (non-null)
     * @param b 끝 박스 (non-null)
     * @param t 보간 파라미터 (보통 [0, 1] 범위)
     * @return 보간된 새 박스
     * @throws NullPointerException {@code a} 또는 {@code b} 가 null
     */
    public static Bbox linearInterpolate(Bbox a, Bbox b, double t) {
        Objects.requireNonNull(a, "a must not be null");
        Objects.requireNonNull(b, "b must not be null");
        return new Bbox(
                a.left + (b.left - a.left) * t,
                a.top + (b.top - a.top) * t,
                a.right + (b.right - a.right) * t,
                a.bottom + (b.bottom - a.bottom) * t
        );
    }
}
