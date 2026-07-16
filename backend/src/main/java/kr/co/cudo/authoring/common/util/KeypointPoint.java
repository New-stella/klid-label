package kr.co.cudo.authoring.common.util;

/**
 * 키포인트(포즈) 좌표 — COCO-native 삼중값 {@code [x, y, v]} (불변).
 *
 * <p>기존 2-튜플 {@link Point} 와 분리된 SKELETON(17-keypoint) 전용 표현이다.
 * 기존 4타입(BBOX/POLYGON/SEGMENT/TRACK)의 2-튜플 직렬화 경로는 이 타입을 사용하지 않는다.
 *
 * @param x 픽셀 x 좌표 (음수 불가 — 검증은 호출부/Service 책임)
 * @param y 픽셀 y 좌표 (음수 불가)
 * @param v 가시성. 0=미표기(x,y 무의미), 1=비가시(occluded), 2=가시(visible)
 */
public record KeypointPoint(double x, double y, int v) {
}
