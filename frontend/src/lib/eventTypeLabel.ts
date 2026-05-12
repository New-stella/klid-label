const CODE_TO_LABEL: Record<string, string> = {
  FALL: '쓰러짐',
  VIOLENCE: '폭력',
  TRAFFIC_ACCIDENT: '교통사고',
  ABNORMAL_BEHAVIOR: '이상행동(유괴)',
  FLOOD: '침수',
  WILDFIRE: '산불',
  EVT_FALL: '쓰러짐',
  EVT_VIOLENCE: '폭력',
  EVT_ACCIDENT: '교통사고',
  EVT_ABNORMAL: '이상행동(유괴)',
  EVT_FLOOD: '침수',
  EVT_FIRE: '산불',
};

/** 이벤트 코드(영문 enum) → 한글 라벨 변환. 매핑 없는 값은 입력 그대로 반환. */
export function getEventTypeLabel(code: string): string {
  return CODE_TO_LABEL[code] ?? code;
}

/** DB EVT_ 접두사 코드 6종 (LS_DATA_RAW.EVNT_TYPE_CD 실제 시드 값). */
export const FIXED_EVENT_TYPE_CODES = [
  'EVT_FALL',
  'EVT_VIOLENCE',
  'EVT_ACCIDENT',
  'EVT_ABNORMAL',
  'EVT_FLOOD',
  'EVT_FIRE',
] as const;
