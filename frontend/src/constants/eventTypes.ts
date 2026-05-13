/**
 * 영상 이벤트 타입 — 시스템 단일 진실(SoT). BE {@code EvntType} enum 과 동기.
 *
 * 6 종 운영: 쓰러짐 / 폭력 / 교통사고 / 이상행동(유괴) / 침수 / 산불.
 */
export const EVENT_TYPES = [
  { code: 'EVT_FALL', label: '쓰러짐' },
  { code: 'EVT_VIOLENCE', label: '폭력' },
  { code: 'EVT_ACCIDENT', label: '교통사고' },
  { code: 'EVT_ABNORMAL', label: '이상행동(유괴)' },
  { code: 'EVT_FLOOD', label: '침수' },
  { code: 'EVT_FIRE', label: '산불' },
] as const;

export type EventTypeCode = (typeof EVENT_TYPES)[number]['code'];

/** 정규 6 종 코드 union (SoT). */
export const EVENT_TYPE_CODES: readonly EventTypeCode[] = EVENT_TYPES.map(
  (e) => e.code,
);

/** code → 한글 라벨 record. */
export const EVENT_TYPE_LABEL: Record<EventTypeCode, string> = Object.fromEntries(
  EVENT_TYPES.map((e) => [e.code, e.label]),
) as Record<EventTypeCode, string>;

/**
 * 통계 API 등에서 사용하는 약어 코드 (BE 외부 계약) → 한글 라벨 폴백.
 *
 * BE {@code StatsService} 가 그리드 6 칸 라벨 정렬을 위해 약어 코드를 사용한다.
 * 라벨 값은 정규 SoT 와 동일하게 유지해야 한다.
 */
const LEGACY_LABEL: Record<string, string> = {
  FALL: '쓰러짐',
  VIOLENCE: '폭력',
  TRAFFIC_ACCIDENT: '교통사고',
  ABNORMAL_BEHAVIOR: '이상행동(유괴)',
  FLOOD: '침수',
  WILDFIRE: '산불',
};

/**
 * 이벤트 코드 → 한글 라벨 변환.
 *
 * - null/undefined/빈 문자열: `'-'` 반환 (목록/카드 빈 셀 표기)
 * - 정규 EVT_ 6 코드: SoT 라벨
 * - 약어 코드 (FALL/TRAFFIC_ACCIDENT 등): 폴백 라벨
 * - 매핑 없는 코드: 입력 그대로 (디버깅 편의)
 */
export function eventTypeLabel(code: string | null | undefined): string {
  if (!code) return '-';
  return EVENT_TYPE_LABEL[code as EventTypeCode] ?? LEGACY_LABEL[code] ?? code;
}
