/**
 * 생성 조건 표기 — 요청할 때 지정한 조건을 **하나도 빠뜨리지 않고** 보여준다.
 *
 * <h3>두 가지를 동시에 지킨다</h3>
 * <ol>
 *   <li><b>아는 다섯 항목은 정해진 차례로 우리말 이름과 함께</b> 보인다 — 시간대 · 계절 · 날씨 ·
 *       지형 · 심각도. 코드도 대응하는 우리말 문구로 바꿔 보인다.</li>
 *   <li><b>모르는 항목·모르는 값은 감추지 않는다.</b> 아는 다섯 뒤에 이름과 값을 받은 그대로
 *       이어 붙인다. 표시 문구를 접수 창구 값역의 사본으로만 두면 항목이 늘어난 날 화면이 그
 *       항목을 조용히 감춘다 — 값이 사라지는 쪽의 실패라 아무도 알아채지 못한다.</li>
 * </ol>
 *
 * ⚠ **구 근거 폐기.** 이 파일은 예전에 *"조건을 이루는 개별 항목과 그 허용 값역은 정하지
 *   않는다"* 와 *"내부 채널 증강 요청 창구의 조건 항목과 값역을 그대로 옮겨 오지 말 것"* 을
 *   근거로 **키를 아예 해석하지 않았다.** 두 문장은 폐기됐고 다섯 항목·닫힌 값역이 확정됐다.
 *   그 근거로 우리말 이름을 다시 걷어내지 말 것.
 * ⚠⚠ 그렇다고 **아는 다섯만 남기고 거르는 것은 여전히 금지**다 — 위 ②는 그대로 유효하다.
 *
 * 어휘의 단일 정의 지점은 {@link ./conditionOptions} 이며 이 파일은 그것을 참조할 뿐 목록을
 * 복제하지 않는다.
 *
 * @design API-231
 * @design API-232
 * @design API-233
 * @design SCREEN-044
 * @design ADR-061
 */

import {
  PORTAL_AUGMENT_CONDITION_KEYS,
  portalAugmentConditionCodeLabel,
  portalAugmentConditionLabel,
} from './conditionOptions';

/**
 * 편친 조건 한 항목.
 *
 * - `key` 는 서버가 준 이름 그대로다(진단·React 키 용도).
 * - `label` 은 아는 항목이면 우리말 이름, 모르는 항목이면 `key` 그대로다.
 * - `value` 는 아는 코드면 우리말 문구, 모르는 값이면 받은 값 그대로다.
 */
export interface GenerationConditionEntry {
  key: string;
  label: string;
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

function toEntry(key: string, raw: unknown): GenerationConditionEntry {
  const value = stringifyValue(raw);
  return {
    key,
    label: portalAugmentConditionLabel(key),
    // 문자열이 아닌 값(배열·객체·숫자)은 코드가 아니므로 표기 치환 대상이 아니다.
    value: typeof raw === 'string' ? portalAugmentConditionCodeLabel(key, value) : value,
  };
}

/**
 * 조건 객체를 표기용 항목 배열로 편다.
 *
 * 차례는 **아는 다섯 항목 먼저(정해진 순서)**, 그 뒤에 **모르는 항목**이다. 모르는 항목끼리는
 * 키 순으로 정렬한다 — 서버 응답의 키 순서가 보장되지 않아, 고정하지 않으면 같은 요청이 조회할
 * 때마다 다른 순서로 보인다.
 *
 * 객체가 아니거나 비어 있으면 빈 배열이다 — 호출측이 {@link EMPTY_CONDITION_TEXT} 로 대신한다.
 */
export function formatGenerationCondition(condition: unknown): GenerationConditionEntry[] {
  if (condition === null || typeof condition !== 'object' || Array.isArray(condition)) return [];
  const source = condition as Record<string, unknown>;
  const known = PORTAL_AUGMENT_CONDITION_KEYS.filter((key) =>
    Object.prototype.hasOwnProperty.call(source, key),
  ).map((key) => toEntry(key, source[key]));
  const knownKeys = new Set<string>(PORTAL_AUGMENT_CONDITION_KEYS);
  const unknown = Object.keys(source)
    .filter((key) => !knownKeys.has(key))
    /*
     * ★코드포인트 순으로 세운다 — `localeCompare` 를 쓰지 않는다.
     *   이 정렬의 목적은 「보기 좋은 차례」가 아니라 **응답의 키 순서와 무관하게 늘 같은 줄**이다.
     *   그런데 `localeCompare` 는 실행 환경의 문자 정렬 자료에 따라 결과가 갈린다 — 한글이 라틴
     *   문자보다 앞에 서기도 뒤에 서기도 한다(실측). 그러면 같은 값이 기계마다 다른 차례로 서서
     *   목적 자체가 깨진다.
     */
    .sort((a, b) => (a < b ? -1 : a > b ? 1 : 0))
    .map((key) => toEntry(key, source[key]));
  return [...known, ...unknown];
}

/** 목록 한 칸에 들어갈 한 줄 요약. 항목이 없으면 {@link EMPTY_CONDITION_TEXT}. */
export function summarizeGenerationCondition(condition: unknown): string {
  const entries = formatGenerationCondition(condition);
  if (entries.length === 0) return EMPTY_CONDITION_TEXT;
  return entries.map((e) => `${e.label}: ${e.value}`).join(' · ');
}
