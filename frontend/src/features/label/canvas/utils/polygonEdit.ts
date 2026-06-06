// 폴리곤 전체 이동 / 꼭짓점 편집 → 이미지 좌표 변환 + 경계 클램프 — Phase C.
// BBOX 이동(bboxEdit)과 동일하게 translateFromCanvas + clampToImage 정책을 재사용한다.
// 폴리곤은 Transformer 스케일 리사이즈를 쓰지 않으므로(점 왜곡) 이동/꼭짓점 편집 전용 헬퍼만 둔다.

import {
  clampToImage,
  translateFromCanvas,
  translateToCanvas,
  type Geometry,
} from './coordinateTransformer';

/**
 * 꼭짓점 앵커를 렌더할 최대 점 수 임계값.
 * SAM2 분할 폴리곤은 수백~1000점까지 나올 수 있어, 임계 초과 시 앵커를 렌더하지 않고
 * (개별 Circle 노드 폭주로 인한 드래그 렉 방지) 전체 이동만 허용한다.
 * 100점 = 200 좌표값.
 */
export const VERTEX_ANCHOR_MAX_POINTS = 100;

/** 점 수가 앵커 렌더 임계 이하인지. points 는 flat [x1,y1,...]. */
export function shouldRenderVertexAnchors(points: number[]): boolean {
  return points.length / 2 <= VERTEX_ANCHOR_MAX_POINTS;
}

/**
 * 폴리곤 전체를 캔버스 delta(dragEnd 시 노드 위치)만큼 이동 → 이미지 좌표 점 배열.
 * - draggable 노드는 dragEnd 시 자기 x/y 가 이동량(캔버스 px)을 가진다. 시작 좌표는 0 으로 렌더하므로
 *   node.x()/y() 가 곧 캔버스 delta 다.
 * - 캔버스 delta 를 이미지 좌표 delta 로 환산(scale 보정)하여 모든 점에 적용.
 * - 경계 클램프: 일부 점만 잘리면 모양이 찌그러지므로, 전체 delta 를 외접 박스 기준으로 클램프해
 *   폴리곤 전체가 이미지 밖으로 나가지 않게 한다(모양 유지).
 *
 * @param imagePoints 현재 이미지 좌표 점 배열 [x1,y1,...]
 * @param canvasDx    드래그 노드의 캔버스 x 이동량 (node.x())
 * @param canvasDy    드래그 노드의 캔버스 y 이동량 (node.y())
 */
export function movePolygonByCanvasDelta(
  geom: Geometry,
  imagePoints: number[],
  canvasDx: number,
  canvasDy: number,
): number[] {
  // 캔버스 delta → 이미지 delta (회전 0 가정 — 폴리곤 편집은 angle=0 캔버스에서만 노출).
  // 원점(left,top)과 (left+dx,top+dy) 를 각각 역변환해 차이를 구하면 scale/회전 보정이 일관된다.
  const o = translateFromCanvas(geom, geom.left, geom.top);
  const m = translateFromCanvas(geom, geom.left + canvasDx, geom.top + canvasDy);
  let imgDx = m.x - o.x;
  let imgDy = m.y - o.y;

  // 외접 박스 기준 delta 클램프 — 전체 폴리곤이 [0, image] 안에 머물도록.
  let minX = Number.POSITIVE_INFINITY;
  let minY = Number.POSITIVE_INFINITY;
  let maxX = Number.NEGATIVE_INFINITY;
  let maxY = Number.NEGATIVE_INFINITY;
  for (let i = 0; i + 1 < imagePoints.length; i += 2) {
    const x = imagePoints[i];
    const y = imagePoints[i + 1];
    if (x < minX) minX = x;
    if (x > maxX) maxX = x;
    if (y < minY) minY = y;
    if (y > maxY) maxY = y;
  }
  // delta 가 박스를 경계 밖으로 밀면 허용 범위로 줄인다.
  imgDx = clampDelta(imgDx, minX, maxX, geom.image.width);
  imgDy = clampDelta(imgDy, minY, maxY, geom.image.height);

  const out: number[] = [];
  for (let i = 0; i + 1 < imagePoints.length; i += 2) {
    out.push(imagePoints[i] + imgDx, imagePoints[i + 1] + imgDy);
  }
  return out;
}

/** 한 축의 delta 를 [0, max] 경계 안으로 클램프 (min/max 는 현재 외접 범위). */
function clampDelta(delta: number, min: number, max: number, imageMax: number): number {
  // 왼쪽/위로 너무 가면: min + delta >= 0
  if (min + delta < 0) return -min;
  // 오른쪽/아래로 너무 가면: max + delta <= imageMax
  if (max + delta > imageMax) return imageMax - max;
  return delta;
}

/**
 * 단일 꼭짓점을 캔버스 좌표(앵커 노드의 x/y)로 이동 → 새 이미지 좌표 점 배열.
 * 개별 점만 이미지 경계로 클램프한다(다른 점 불변, 새 배열 반환).
 *
 * @param vertexIndex 점 인덱스 (0-base, flat 배열 기준 i*2)
 * @param canvasX     앵커 노드의 캔버스 x
 * @param canvasY     앵커 노드의 캔버스 y
 */
export function moveVertexToCanvas(
  geom: Geometry,
  imagePoints: number[],
  vertexIndex: number,
  canvasX: number,
  canvasY: number,
): number[] {
  const i = vertexIndex * 2;
  if (i < 0 || i + 1 >= imagePoints.length) return imagePoints;
  const img = clampToImage(geom, translateFromCanvas(geom, canvasX, canvasY));
  const out = [...imagePoints];
  out[i] = img.x;
  out[i + 1] = img.y;
  return out;
}

/** 이미지 좌표 점 배열 → 캔버스 좌표 flat 배열 (렌더용). */
export function imagePointsToCanvas(geom: Geometry, imagePoints: number[]): number[] {
  const out: number[] = [];
  for (let i = 0; i + 1 < imagePoints.length; i += 2) {
    const p = translateToCanvas(geom, imagePoints[i], imagePoints[i + 1]);
    out.push(p.x, p.y);
  }
  return out;
}
