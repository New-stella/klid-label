// CVAT 좌표 변환/회전 유틸 — TypeScript 포팅
// 출처: docs/analysis/portable-modules/06-coordinate-conversion.md
// Adapted from CVAT cvat-canvas (https://github.com/cvat-ai/cvat) — MIT License

export interface Size {
  width: number;
  height: number;
}

/**
 * 캔버스 표시 상태.
 * - image: 원본 이미지 크기 (픽셀)
 * - canvas: 캔버스 DOM 영역 (CSS 픽셀)
 * - scale: 줌 비율 (1.0 = 100%)
 * - top/left: 캔버스 내 이미지 좌상단 오프셋 (CSS 픽셀)
 * - angle: 회전 각도 (degree, 이미지 중심 기준)
 */
export interface Geometry {
  image: Size;
  canvas: Size;
  scale: number;
  top: number;
  left: number;
  angle: number;
}

export interface Point {
  x: number;
  y: number;
}

export interface BoundingBox {
  left: number;
  top: number;
  right: number;
  bottom: number;
}

/**
 * image 좌표 → canvas 좌표.
 * 스케일/오프셋 적용 + 이미지 중심 기준 회전.
 */
export function translateToCanvas(geom: Geometry, imageX: number, imageY: number): Point {
  const cx = geom.image.width / 2;
  const cy = geom.image.height / 2;
  // 회전: 이미지 중심 기준으로 angle 회전
  const [rx, ry] = rotateSinglePoint(cx, cy, geom.angle, imageX, imageY);
  return {
    x: rx * geom.scale + geom.left,
    y: ry * geom.scale + geom.top,
  };
}

/**
 * canvas 좌표 → image 좌표 (translateToCanvas 역변환).
 */
export function translateFromCanvas(geom: Geometry, canvasX: number, canvasY: number): Point {
  const cx = geom.image.width / 2;
  const cy = geom.image.height / 2;
  const rx = (canvasX - geom.left) / geom.scale;
  const ry = (canvasY - geom.top) / geom.scale;
  // 역회전 (-angle)
  const [ix, iy] = rotateSinglePoint(cx, cy, -geom.angle, rx, ry);
  return { x: ix, y: iy };
}

/**
 * 점 배열을 (cx, cy) 기준 angle(degree) 회전.
 * points: [x1, y1, x2, y2, ...]
 */
export function rotate2DPoints(
  cx: number,
  cy: number,
  angleDeg: number,
  points: ArrayLike<number>,
): number[] {
  const rad = (Math.PI / 180) * angleDeg;
  const cos = Math.cos(rad);
  const sin = Math.sin(rad);
  const out: number[] = [];
  for (let i = 0; i + 1 < points.length; i += 2) {
    const x = points[i];
    const y = points[i + 1];
    out.push((x - cx) * cos - (y - cy) * sin + cx, (y - cy) * cos + (x - cx) * sin + cy);
  }
  return out;
}

function rotateSinglePoint(
  cx: number,
  cy: number,
  angleDeg: number,
  x: number,
  y: number,
): [number, number] {
  if (angleDeg === 0) return [x, y];
  const rad = (Math.PI / 180) * angleDeg;
  const cos = Math.cos(rad);
  const sin = Math.sin(rad);
  return [(x - cx) * cos - (y - cy) * sin + cx, (y - cy) * cos + (x - cx) * sin + cy];
}

/**
 * 점 배열의 외접 사각형 계산.
 */
export function computeWrappingBox(points: ArrayLike<number>): BoundingBox {
  let left = Number.POSITIVE_INFINITY;
  let top = Number.POSITIVE_INFINITY;
  let right = Number.NEGATIVE_INFINITY;
  let bottom = Number.NEGATIVE_INFINITY;
  for (let i = 0; i + 1 < points.length; i += 2) {
    const x = points[i];
    const y = points[i + 1];
    if (x < left) left = x;
    if (x > right) right = x;
    if (y < top) top = y;
    if (y > bottom) bottom = y;
  }
  return { left, top, right, bottom };
}

/**
 * 값 클램프 (이미지 경계 검증 보안 가드용).
 */
export function clamp(value: number, min: number, max: number): number {
  return Math.min(Math.max(value, min), max);
}

/**
 * 좌표를 이미지 경계 안으로 강제 (보안: 사용자 입력 좌표 검증).
 */
export function clampToImage(geom: Geometry, point: Point): Point {
  return {
    x: clamp(point.x, 0, geom.image.width),
    y: clamp(point.y, 0, geom.image.height),
  };
}
