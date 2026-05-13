import { EVENT_TYPE_CODES, eventTypeLabel } from '@/constants/eventTypes';

/**
 * 이벤트 코드(영문 enum) → 한글 라벨 변환.
 *
 * SoT: {@link eventTypeLabel} ({@code @/constants/eventTypes}). 본 함수는 외부 인터페이스 유지를
 * 위해 보존한다. 매핑 없는 값은 입력 그대로 반환된다 ({@code eventTypeLabel} 동작).
 *
 * @deprecated 신규 코드는 {@link eventTypeLabel} 을 직접 사용하라.
 */
export function getEventTypeLabel(code: string): string {
  return eventTypeLabel(code);
}

/**
 * DB EVT_ 접두사 코드 6 종 (LS_DATA_RAW.EVNT_TYPE_CD 실제 시드 값).
 *
 * SoT: {@code @/constants/eventTypes} 의 {@code EVENT_TYPE_CODES}.
 */
export const FIXED_EVENT_TYPE_CODES = EVENT_TYPE_CODES;
