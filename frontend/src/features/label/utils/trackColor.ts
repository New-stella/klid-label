// 라벨 trackId → 시각화 색상 매핑 헬퍼 (Phase 5).
//
// 같은 trackId 는 항상 같은 HSL 색을 반환하여 라벨 패널/캔버스에서 동일 객체를
// 색으로 식별할 수 있게 한다. null/undefined/빈 문자열은 회색 fallback.
//
// 보안: 사용자 입력 trackId 는 단순 해시에만 사용되며 DOM 에 직접 삽입되지 않는다.

/**
 * trackId 문자열을 결정적 HSL 색상 문자열로 변환한다.
 *
 * - 같은 trackId → 같은 색 (참조 무결성)
 * - null/undefined/빈 문자열 → 회색 `hsl(0, 0%, 60%)`
 * - 해시: 각 문자 charCodeAt 의 31진수 누적 → 0~359 범위 hue
 *
 * @param trackId 라벨에 부여된 트래커 ID (BE: VARCHAR(64), nullable)
 */
export function trackIdToColor(trackId: string | null | undefined): string {
  if (!trackId) return 'hsl(0, 0%, 60%)';
  let h = 0;
  for (let i = 0; i < trackId.length; i += 1) {
    h = (h * 31 + trackId.charCodeAt(i)) % 360;
  }
  return `hsl(${h}, 70%, 50%)`;
}
