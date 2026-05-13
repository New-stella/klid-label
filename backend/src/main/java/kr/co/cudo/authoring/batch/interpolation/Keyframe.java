package kr.co.cudo.authoring.batch.interpolation;

/**
 * 트랙의 키프레임 — 특정 frame 의 BBOX + outside 마커.
 *
 * <p>{@code outside=true} 면 트랙이 해당 frame 부터 화면 밖으로 나갔음을 의미하는 종료 마커이며,
 * 보간 알고리즘은 이 마커 이후를 처리하지 않는다 (CVAT 와 동일).</p>
 *
 * <p>원본: CVAT 트랙 보간 — {@code docs/analysis/portable-modules/01-track-interpolation.md}</p>
 *
 * @param frame   프레임 번호 (0-based)
 * @param bbox    해당 프레임의 박스 (non-null)
 * @param outside 트랙 종료 마커 여부
 */
public record Keyframe(int frame, Bbox bbox, boolean outside) {
}
