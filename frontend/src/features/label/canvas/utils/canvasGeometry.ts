// 캔버스 Geometry 계산 유틸 — pure 함수 (테스트 가능).

import type { Geometry, Size } from './coordinateTransformer';

/**
 * 이미지 + 캔버스 크기와 zoom/pan으로부터 Geometry 생성.
 * - fit 모드: 이미지가 캔버스에 들어가는 최대 scale (편차 zoom 1.0 기준).
 * - top/left: zoom 후 이미지 좌상단이 캔버스에서 위치하는 오프셋.
 */
export function buildGeometry(
  image: Size,
  canvas: Size,
  zoom: number,
  panX: number,
  panY: number,
  angle = 0,
): Geometry {
  const fitScale = Math.min(canvas.width / image.width, canvas.height / image.height);
  const scale = fitScale * zoom;
  const scaledW = image.width * scale;
  const scaledH = image.height * scale;
  const left = (canvas.width - scaledW) / 2 + panX;
  const top = (canvas.height - scaledH) / 2 + panY;
  return { image, canvas, scale, top, left, angle };
}

/**
 * BBox 정규화 (left < right, top < bottom 보장).
 */
export function normalizeBox(left: number, top: number, right: number, bottom: number) {
  return {
    left: Math.min(left, right),
    top: Math.min(top, bottom),
    right: Math.max(left, right),
    bottom: Math.max(top, bottom),
  };
}

/**
 * BBox 최소 크기(픽셀) 검증 — drag 거리가 너무 작으면 무효.
 */
export function isValidBox(
  left: number,
  top: number,
  right: number,
  bottom: number,
  minSize = 2,
): boolean {
  return Math.abs(right - left) >= minSize && Math.abs(bottom - top) >= minSize;
}
