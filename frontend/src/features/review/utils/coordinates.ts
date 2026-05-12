// SCR-REVIEW-002 Phase 3 — 좌표 변환 유틸 (CVAT portable-modules/06 참고).
//
// 라벨 좌표는 원본 이미지 픽셀 기준이므로, Konva Stage 의 scaleX/scaleY 로
// 통째로 변환하는 패턴을 사용한다. 이 모듈은 aspect-fit 스케일/오프셋만 산출한다.
//
// pure 함수 — 테스트 가능.

export interface FitResult {
  /** aspect-fit 스케일 (이미지 → 캔버스). 이미지가 컨테이너에 들어가는 최대 배율 */
  scale: number;
  /** 캔버스 좌측 여백 (중앙 정렬) */
  offsetX: number;
  /** 캔버스 상단 여백 (중앙 정렬) */
  offsetY: number;
}

/**
 * 이미지가 컨테이너에 aspect-fit 으로 들어갈 때의 scale + offset 계산.
 *
 * - 이미지/컨테이너 어느 한쪽이 0 이하인 경우 안전한 기본값 반환 (NaN/Infinity 방지).
 * - offset 은 컨테이너 중앙 배치 (좌우/상하 균등 여백).
 */
export function getFitScale(
  imageW: number,
  imageH: number,
  containerW: number,
  containerH: number,
): FitResult {
  if (imageW <= 0 || imageH <= 0 || containerW <= 0 || containerH <= 0) {
    return { scale: 1, offsetX: 0, offsetY: 0 };
  }
  const scale = Math.min(containerW / imageW, containerH / imageH);
  const scaledW = imageW * scale;
  const scaledH = imageH * scale;
  const offsetX = (containerW - scaledW) / 2;
  const offsetY = (containerH - scaledH) / 2;
  return { scale, offsetX, offsetY };
}

/**
 * points 배열에서 bounding box (x, y, w, h) 산출.
 *
 * - 2점 (대각선) / 4점 (사각형) / N점 (폴리곤) 모두 지원.
 * - 빈 배열인 경우 0 사각형 반환.
 */
export function pointsToBBox(points: number[][]): {
  x: number;
  y: number;
  width: number;
  height: number;
} {
  if (!points || points.length === 0) {
    return { x: 0, y: 0, width: 0, height: 0 };
  }
  let minX = Infinity;
  let minY = Infinity;
  let maxX = -Infinity;
  let maxY = -Infinity;
  for (const p of points) {
    if (!p || p.length < 2) continue;
    const [x, y] = p;
    if (typeof x !== 'number' || typeof y !== 'number') continue;
    if (x < minX) minX = x;
    if (y < minY) minY = y;
    if (x > maxX) maxX = x;
    if (y > maxY) maxY = y;
  }
  if (minX === Infinity) {
    return { x: 0, y: 0, width: 0, height: 0 };
  }
  return { x: minX, y: minY, width: maxX - minX, height: maxY - minY };
}

/**
 * points 2D 배열을 Konva Line 의 flat number[] 로 평탄화.
 * [[x1,y1],[x2,y2]] → [x1,y1,x2,y2]
 */
export function flattenPoints(points: number[][]): number[] {
  const out: number[] = [];
  if (!points) return out;
  for (const p of points) {
    if (!p || p.length < 2) continue;
    const [x, y] = p;
    if (typeof x !== 'number' || typeof y !== 'number') continue;
    out.push(x, y);
  }
  return out;
}
