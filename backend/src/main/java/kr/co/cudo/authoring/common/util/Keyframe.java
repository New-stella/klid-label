package kr.co.cudo.authoring.common.util;

import java.util.List;

/**
 * 트랙 보간 입력 키프레임.
 * - frameNo: 프레임 번호 (오름차순 정렬 필수)
 * - type: shape 타입
 * - points: shape 좌표 (RECTANGLE 은 [topLeft, bottomRight], ELLIPSE 는 [center, axis], CUBOID 는 8 vertices)
 * - rotation: 회전각 (degree)
 */
public record Keyframe(int frameNo, ShapeType type, List<Point> points, double rotation) {
}
