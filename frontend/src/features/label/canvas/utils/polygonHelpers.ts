// Polygon 도구 보조 유틸 — pure 함수.

/**
 * Polygon은 최소 3개 정점 필요. 미달이면 null 반환 (그리기 취소).
 */
export function validatePolygonPoints(points: number[]): number[] | null {
  if (points.length < 6) return null; // 3 points × (x, y)
  return points;
}

/**
 * Douglas-Peucker 폴리곤 단순화 — flat points [x1,y1,x2,y2,...] 입력/출력.
 * 자석 올가미 getContour 결과는 픽셀 단위 조밀 경로라, 커밋 전 정점 수를 줄여 렌더/저장 부하를 낮춘다.
 * - epsilon: 단순화 허용 오차(픽셀). 클수록 정점 감소.
 * - maxPoints: 단순화 후에도 상한 초과 시 epsilon 을 키워 재시도 (정점 수 폭주 방지, 보안 CWE-20).
 */
export function simplifyPolygon(points: number[], epsilon = 1.0, maxPoints = 1000): number[] {
  if (points.length <= 6) return points;
  let eps = epsilon;
  let result = douglasPeucker(points, eps);
  let guard = 0;
  while (result.length / 2 > maxPoints && guard < 20) {
    eps *= 1.8;
    result = douglasPeucker(points, eps);
    guard += 1;
  }
  // 그래도 상한 초과면 균등 샘플링으로 강제 절삭
  if (result.length / 2 > maxPoints) {
    const sampled: number[] = [];
    const total = result.length / 2;
    const step = Math.ceil(total / maxPoints);
    for (let i = 0; i < total; i += step) {
      sampled.push(result[i * 2], result[i * 2 + 1]);
    }
    return sampled;
  }
  return result;
}

function douglasPeucker(points: number[], epsilon: number): number[] {
  const n = points.length / 2;
  if (n < 3) return points;
  const keep = new Array<boolean>(n).fill(false);
  keep[0] = true;
  keep[n - 1] = true;
  simplifySection(points, 0, n - 1, epsilon, keep);
  const out: number[] = [];
  for (let i = 0; i < n; i += 1) {
    if (keep[i]) out.push(points[i * 2], points[i * 2 + 1]);
  }
  return out;
}

function simplifySection(
  points: number[],
  first: number,
  last: number,
  epsilon: number,
  keep: boolean[],
): void {
  if (last <= first + 1) return;
  const ax = points[first * 2];
  const ay = points[first * 2 + 1];
  const bx = points[last * 2];
  const by = points[last * 2 + 1];
  let maxDist = -1;
  let index = -1;
  for (let i = first + 1; i < last; i += 1) {
    const d = perpendicularDistance(points[i * 2], points[i * 2 + 1], ax, ay, bx, by);
    if (d > maxDist) {
      maxDist = d;
      index = i;
    }
  }
  if (maxDist > epsilon && index !== -1) {
    keep[index] = true;
    simplifySection(points, first, index, epsilon, keep);
    simplifySection(points, index, last, epsilon, keep);
  }
}

function perpendicularDistance(
  px: number,
  py: number,
  ax: number,
  ay: number,
  bx: number,
  by: number,
): number {
  const dx = bx - ax;
  const dy = by - ay;
  const len = Math.hypot(dx, dy);
  if (len === 0) return Math.hypot(px - ax, py - ay);
  return Math.abs((px - ax) * dy - (py - ay) * dx) / len;
}

/**
 * Polygon 닫기 (마지막 점이 첫 점에 가까우면 자동 닫힘).
 */
export function closePolygonIfNear(
  points: number[],
  threshold = 8,
): { closed: boolean; points: number[] } {
  if (points.length < 6) return { closed: false, points };
  const x0 = points[0];
  const y0 = points[1];
  const xn = points[points.length - 2];
  const yn = points[points.length - 1];
  const dist = Math.hypot(xn - x0, yn - y0);
  if (dist <= threshold) {
    // 끝 점 제거
    return { closed: true, points: points.slice(0, -2) };
  }
  return { closed: false, points };
}
