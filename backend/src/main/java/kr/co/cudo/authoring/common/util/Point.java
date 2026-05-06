package kr.co.cudo.authoring.common.util;

/**
 * 2D 좌표 (불변).
 * 라벨 좌표 / CVAT 포팅 유틸의 공통 좌표 표현.
 * - x, y: 픽셀 좌표 (음수 허용 X — 좌표 검증은 호출부 책임)
 */
public record Point(double x, double y) {
}
