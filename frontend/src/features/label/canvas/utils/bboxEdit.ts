// 기존 BBOX 이동/리사이즈 → 이미지 좌표 변환 + 경계 클램프 — R17 이슈4.
// konva 노드의 캔버스 사각형(x/y/width/height)을 이미지 좌표 BBOX 로 환산한다.
// 신규 드로잉(OverlayLayer.commitBbox)과 동일한 translateFromCanvas + clampToImage 정책을 재사용.

import { isValidBox, normalizeBox } from './canvasGeometry';
import {
  clampToImage,
  translateFromCanvas,
  type BoundingBox,
  type Geometry,
} from './coordinateTransformer';

/** 최소 박스 크기(이미지 픽셀) — 이 미만이면 무효 처리. */
export const MIN_BOX_SIZE = 2;

export interface CanvasRect {
  x: number;
  y: number;
  width: number;
  height: number;
}

/**
 * 캔버스 좌표 사각형 → 이미지 좌표 BBOX.
 * - scale/pan 보정: translateFromCanvas 로 두 꼭짓점을 이미지 좌표로 환산
 * - 경계 클램프: clampToImage 로 이미지 밖 좌표 차단
 * - 정규화 후 최소 크기 미달이면 null (무효 — 0/음수 크기 방어)
 */
export function canvasRectToImageBox(geom: Geometry, rect: CanvasRect): BoundingBox | null {
  const tl = clampToImage(geom, translateFromCanvas(geom, rect.x, rect.y));
  const br = clampToImage(geom, translateFromCanvas(geom, rect.x + rect.width, rect.y + rect.height));
  const norm = normalizeBox(tl.x, tl.y, br.x, br.y);
  if (!isValidBox(norm.left, norm.top, norm.right, norm.bottom, MIN_BOX_SIZE)) return null;
  return norm;
}
