// Polygon 도구 보조 유틸 — pure 함수.

/**
 * Polygon은 최소 3개 정점 필요. 미달이면 null 반환 (그리기 취소).
 */
export function validatePolygonPoints(points: number[]): number[] | null {
  if (points.length < 6) return null; // 3 points × (x, y)
  return points;
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
