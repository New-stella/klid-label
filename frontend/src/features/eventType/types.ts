// 이벤트 타입 도메인 타입 — BE: GET /api/v1/event-types, /event-types/labels 정합.
//
// 저작도구 소유 이벤트유형 마스터(LS_EVNT_TYPE) 기반. 표시 단위는 <이벤트유형> 1건이다.

/**
 * 이벤트 타입 필터 옵션 — BE `EventTypeResponse` 1:1 미러.
 *
 * ★필드명은 하위호환을 위해 유지하지만 값의 입도가 바뀌었다(축: 카테고리 → 유형).
 * - categoryKey: 필터 키 = 이벤트유형코드 (예 "EV01000101"). 드롭다운 value.
 * - label: 이벤트명 (예 "침수(범람)"). 드롭다운 표시. 이름 미등록이면 유형코드가 온다.
 * - memberCodes: 이 옵션이 매칭하는 EV-코드 목록 — 유형 축이라 항상 1건.
 */
export interface EventTypeOption {
  categoryKey: string;
  label: string;
  memberCodes: string[];
}

/**
 * 이벤트 코드 → 이벤트명 맵 — BE `/event-types/labels` 응답.
 *
 * 등록된 전체 유형(비수집 EV07000201→"기타 상황" 포함)을 이벤트명으로 해석한다.
 * 미등록/이름없는 코드는 맵에 없으므로 소비측에서 원문 폴백한다.
 */
export type EventTypeLabelMap = Record<string, string>;
