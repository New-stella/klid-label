// Phase 4 — 오토라벨/추적 결과를 작업본에 병합할 때 중복 검출을 스킵하기 위한 순수 유틸.
//
// 정책(HIGH #4):
//  - 같은 클래스(라벨명/labelId)일 때만 IoU 를 비교한다. 다른 클래스는 겹쳐도 유지(스킵 안 함).
//  - IoU ≥ 임계값(보수적 기본 0.7)이면 검출 결과를 스킵(기존 라벨과 중복으로 간주).
//  - BBOX-BBOX 는 정확한 bbox IoU, POLYGON-POLYGON 은 폴리곤 IoU(샘플링), 서로 다른 shape.type
//    조합은 보수적으로 "중복 아님"(IoU=0)으로 처리해 유지한다(오검출로 실제 라벨을 지우는 위험 회피).
//
// 순수 함수(부작용 없음) — 유닛 테스트로 엣지케이스를 커버한다.

import type { Label, Shape } from '../types';

/** 검출 중복 스킵 임계값(보수적 기본). IoU 가 이 값 이상이면 같은 객체로 간주. */
export const DEDUP_IOU_THRESHOLD = 0.7;

/** shape 의 외접 bbox [x1,y1,x2,y2] (좌상단→우하단 정규화). 계산 불가 시 null. */
export function boundingBoxOf(shape: Shape): [number, number, number, number] | null {
  if (shape.type === 'BBOX') {
    const x1 = Math.min(shape.left, shape.right);
    const x2 = Math.max(shape.left, shape.right);
    const y1 = Math.min(shape.top, shape.bottom);
    const y2 = Math.max(shape.top, shape.bottom);
    return [x1, y1, x2, y2];
  }
  if (shape.type === 'POLYGON') {
    return flatBbox(shape.points);
  }
  return null;
}

function flatBbox(pts: number[]): [number, number, number, number] | null {
  if (!Array.isArray(pts) || pts.length < 6) return null; // 최소 3점
  let minX = Infinity;
  let minY = Infinity;
  let maxX = -Infinity;
  let maxY = -Infinity;
  for (let i = 0; i + 1 < pts.length; i += 2) {
    const x = pts[i];
    const y = pts[i + 1];
    if (x < minX) minX = x;
    if (x > maxX) maxX = x;
    if (y < minY) minY = y;
    if (y > maxY) maxY = y;
  }
  if (!Number.isFinite(minX) || !Number.isFinite(minY)) return null;
  return [minX, minY, maxX, maxY];
}

/** 축정렬 bbox IoU. 두 박스가 겹치지 않으면 0. */
export function bboxIoU(
  a: [number, number, number, number],
  b: [number, number, number, number],
): number {
  const ix1 = Math.max(a[0], b[0]);
  const iy1 = Math.max(a[1], b[1]);
  const ix2 = Math.min(a[2], b[2]);
  const iy2 = Math.min(a[3], b[3]);
  const iw = ix2 - ix1;
  const ih = iy2 - iy1;
  if (iw <= 0 || ih <= 0) return 0;
  const inter = iw * ih;
  const areaA = Math.max(0, a[2] - a[0]) * Math.max(0, a[3] - a[1]);
  const areaB = Math.max(0, b[2] - b[0]) * Math.max(0, b[3] - b[1]);
  const union = areaA + areaB - inter;
  if (union <= 0) return 0;
  return inter / union;
}

function pointInPolygon(x: number, y: number, poly: number[]): boolean {
  let inside = false;
  const n = poly.length / 2;
  for (let i = 0, j = n - 1; i < n; j = i++) {
    const xi = poly[2 * i];
    const yi = poly[2 * i + 1];
    const xj = poly[2 * j];
    const yj = poly[2 * j + 1];
    const intersect =
      yi > y !== yj > y && x < ((xj - xi) * (y - yi)) / (yj - yi + Number.EPSILON) + xi;
    if (intersect) inside = !inside;
  }
  return inside;
}

/**
 * 폴리곤 IoU — 임의(오목 포함) 폴리곤을 지원하기 위해 union bbox 를 격자로 샘플링해 근사한다.
 * 좌표가 부족/비정상이면 외접 bbox IoU 로 fallback.
 */
export function polygonIoU(a: number[], b: number[]): number {
  const ba = flatBbox(a);
  const bb = flatBbox(b);
  if (!ba || !bb) return 0;
  // 겹침 자체가 없으면 즉시 0 (샘플링 스킵).
  if (bboxIoU(ba, bb) === 0) return 0;

  const minX = Math.min(ba[0], bb[0]);
  const minY = Math.min(ba[1], bb[1]);
  const maxX = Math.max(ba[2], bb[2]);
  const maxY = Math.max(ba[3], bb[3]);
  const w = maxX - minX;
  const h = maxY - minY;
  if (w <= 0 || h <= 0) return 0;

  const GRID = 64;
  let inA = 0;
  let inB = 0;
  let inBoth = 0;
  for (let gx = 0; gx < GRID; gx += 1) {
    const x = minX + ((gx + 0.5) / GRID) * w;
    for (let gy = 0; gy < GRID; gy += 1) {
      const y = minY + ((gy + 0.5) / GRID) * h;
      const pa = pointInPolygon(x, y, a);
      const pb = pointInPolygon(x, y, b);
      if (pa) inA += 1;
      if (pb) inB += 1;
      if (pa && pb) inBoth += 1;
    }
  }
  const union = inA + inB - inBoth;
  if (union <= 0) return 0;
  return inBoth / union;
}

/**
 * 두 shape 의 IoU. 같은 타입만 실제 계산하고, 서로 다른 타입(BBOX vs POLYGON 등)은 보수적으로
 * 0(중복 아님)을 반환한다 — 다른 형태를 겹친다는 이유로 라벨을 지우지 않기 위함.
 */
export function shapeIoU(a: Shape, b: Shape): number {
  if (a.type === 'BBOX' && b.type === 'BBOX') {
    const ba = boundingBoxOf(a);
    const bb = boundingBoxOf(b);
    if (!ba || !bb) return 0;
    return bboxIoU(ba, bb);
  }
  if (a.type === 'POLYGON' && b.type === 'POLYGON') {
    return polygonIoU(a.points, b.points);
  }
  // 서로 다른 shape.type 조합 → 보수적으로 중복 아님.
  return 0;
}

/** 같은 클래스 판정 — 양쪽 labelId 가 있으면 labelId, 아니면 className 으로 비교. */
export function sameClass(a: Label, b: Label): boolean {
  if (a.labelId != null && b.labelId != null) {
    return a.labelId === b.labelId;
  }
  return a.className === b.className;
}

/**
 * 검출 결과를 기존 라벨과 대조해 중복(같은 클래스 + IoU≥임계값)을 스킵하고, 유지할 검출만 반환한다.
 * 기존 라벨뿐 아니라 이미 유지 확정된 검출끼리도 대조해 검출 세트 내 중복도 제거한다.
 *
 * @param existing      현재 작업본 라벨(보존 대상)
 * @param detected      새 검출/추적 결과
 * @param iouThreshold  중복 판정 임계값(기본 {@link DEDUP_IOU_THRESHOLD})
 * @returns 병합해도 되는(중복이 아닌) 검출 라벨 목록
 */
export function mergeDetections(
  existing: Label[],
  detected: Label[],
  iouThreshold: number = DEDUP_IOU_THRESHOLD,
): Label[] {
  const kept: Label[] = [];
  for (const det of detected) {
    const isDup = (pool: Label[]) =>
      pool.some((ex) => sameClass(ex, det) && shapeIoU(ex.shape, det.shape) >= iouThreshold);
    if (isDup(existing) || isDup(kept)) continue;
    kept.push(det);
  }
  return kept;
}
