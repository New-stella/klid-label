// SCR-REVIEW-002 Phase 3 — 카테고리별 라벨 색상 매핑.
//
// 자주 쓰이는 카테고리는 mock UI 와 일관성을 위해 고정 색상 사용.
// 그 외는 라벨명 hash → HSL (golden ratio 분포) 로 안정적·고유한 색상 생성.
//
// pure 함수 — 동일 입력 → 동일 출력.

const FIXED_COLORS: Record<string, string> = {
  오토바이: '#22d3ee', // cyan-400
  차량: '#eab308', // amber-500
  트럭: '#22c55e', // green-500
  자전거: '#06b6d4', // cyan-500
  사람: '#ef4444', // red-500
  화재: '#f97316', // orange-500
};

/**
 * 문자열 hash (FNV-like 32bit). 보안 용도 아님 — 색상 분배용.
 */
function hashString(s: string): number {
  let h = 0;
  for (let i = 0; i < s.length; i += 1) {
    h = (h * 31 + s.charCodeAt(i)) >>> 0;
  }
  return h;
}

/**
 * 카테고리 라벨 → 안정적인 색상 (HEX 또는 HSL 문자열).
 *
 * - 고정 매핑된 라벨은 mock UI 색상 그대로 반환.
 * - 미정의 라벨은 hash 기반 HSL — 동일 입력은 항상 동일 출력.
 */
export function colorForLabel(label: string): string {
  if (!label) return '#94a3b8'; // slate-400 (라벨명 없음)
  const fixed = FIXED_COLORS[label];
  if (fixed) return fixed;
  const hue = hashString(label) % 360;
  return `hsl(${hue}, 65%, 55%)`;
}

/**
 * fill 색상용 — stroke 색상에 알파 추가.
 * stroke 가 HEX 면 8자리 HEX, HSL 이면 hsla 로 변환.
 */
export function fillForLabel(label: string, alpha = 0.15): string {
  const stroke = colorForLabel(label);
  if (stroke.startsWith('#')) {
    const a = Math.round(Math.max(0, Math.min(1, alpha)) * 255)
      .toString(16)
      .padStart(2, '0');
    return `${stroke}${a}`;
  }
  if (stroke.startsWith('hsl(')) {
    // hsl(h, s%, l%) → hsla(h, s%, l%, alpha)
    const inner = stroke.slice(4, -1);
    return `hsla(${inner}, ${alpha})`;
  }
  return stroke;
}
