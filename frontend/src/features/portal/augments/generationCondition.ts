/**
 * 생성 조건 표기 — 요청할 때 지정한 조건을 **항목 이름을 지어내지 않고** 그대로 보여준다.
 *
 * <h3>왜 매핑표가 없나</h3>
 * 두 조회 창구와 접수 창구가 모두 *"조건을 이루는 개별 항목과 그 허용 값역은 이 산출물에서
 * 정하지 않는다"* 고 적고, 접수 창구(API-231)는 한 걸음 더 나아가 *"내부 채널 증강 요청 창구의
 * 조건 항목과 값역을 그대로 옮겨 오지 말 것 — 두 창구는 인가 주체와 대상 계보가 달라 같은
 * 규칙이 성립한다는 근거가 없다"* 고 못박는다.
 *
 * ⇒ 그러므로 이 파일은 **키를 해석하지 않는다.** 서버가 준 객체를 키 순서로 정렬해 그대로 편다.
 *   한글 라벨 매핑을 여기 두면 그것이 곧 계약의 사본이 되고, 서버가 항목을 넓히는 순간 화면이
 *   **모르는 항목을 조용히 감춘다**(값이 사라지는 쪽의 실패라 아무도 알아채지 못한다).
 *
 * @design API-232
 * @design API-233
 * @design SCREEN-044
 */

/** 편친 조건 한 항목. `key` 는 서버가 준 이름 그대로다(번역하지 않는다). */
export interface GenerationConditionEntry {
  key: string;
  value: string;
}

/** 조건이 비었을 때의 표기. 「없음」이라고 단정하지 않는다 — 서버가 안 준 것과 구분되지 않는다. */
export const EMPTY_CONDITION_TEXT = '-';

function stringifyValue(value: unknown): string {
  if (value === null || value === undefined) return '';
  if (typeof value === 'string') return value;
  if (typeof value === 'number' || typeof value === 'boolean') return String(value);
  if (Array.isArray(value)) return value.map(stringifyValue).join(', ');
  try {
    return JSON.stringify(value) ?? '';
  } catch {
    // 순환 참조 등 — 값을 지어내지 않고 비운다.
    return '';
  }
}

/**
 * 조건 객체를 표기용 항목 배열로 편다.
 *
 * 객체가 아니거나 비어 있으면 빈 배열이다 — 호출측이 {@link EMPTY_CONDITION_TEXT} 로 대신한다.
 * 정렬을 키 기준으로 고정하는 이유는 서버 응답의 키 순서가 보장되지 않아, 고정하지 않으면
 * 같은 요청이 조회할 때마다 다른 순서로 보이기 때문이다.
 */
export function formatGenerationCondition(condition: unknown): GenerationConditionEntry[] {
  if (condition === null || typeof condition !== 'object' || Array.isArray(condition)) return [];
  return Object.entries(condition as Record<string, unknown>)
    .map(([key, value]) => ({ key, value: stringifyValue(value) }))
    .sort((a, b) => a.key.localeCompare(b.key));
}

/** 목록 한 칸에 들어갈 한 줄 요약. 항목이 없으면 {@link EMPTY_CONDITION_TEXT}. */
export function summarizeGenerationCondition(condition: unknown): string {
  const entries = formatGenerationCondition(condition);
  if (entries.length === 0) return EMPTY_CONDITION_TEXT;
  return entries.map((e) => `${e.key}: ${e.value}`).join(' · ');
}
