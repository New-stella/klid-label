package kr.co.cudo.authoring.batch.interpolation;

import kr.co.cudo.authoring.common.util.Point;

import java.util.List;

/**
 * 폴리곤/폴리라인 트랙의 키프레임 — 특정 frame 의 정점 목록 + outside 마커.
 *
 * <p>{@code outside=true} 면 트랙이 해당 frame 부터 화면 밖으로 나갔음을 의미하는 종료 마커이며,
 * 보간 알고리즘은 이 마커 이후를 처리하지 않는다 (BBOX {@link Keyframe} 와 동일 정책).</p>
 *
 * @param frame   프레임 번호 (0-based)
 * @param points  해당 프레임의 정점 목록 (non-null)
 * @param outside 트랙 종료 마커 여부
 */
public record PolyKeyframe(int frame, List<Point> points, boolean outside) {
}
