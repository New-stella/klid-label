// 이벤트 타입 도메인 타입 — BE: GET /api/v1/event-types, /event-types/labels 정합.
//
// 저작도구 소유 이벤트유형 마스터(LS_EVNT_TYPE) 기반. 표시 단위는 <표시명 그룹> 1건이다.

/**
 * 이벤트 타입 필터 옵션 — BE `EventTypeResponse` 1:1 미러.
 *
 * ★필드명은 하위호환을 위해 유지하지만 값의 입도가 바뀌었다(축: 카테고리 → 유형 → 표시명 그룹).
 * - categoryKey: 필터 키 = 표시명 그룹의 대표 유형코드(그룹 내 최소 코드, 예 "EV01000101").
 *   드롭다운 value. BE 가 이 값을 그룹 전체 코드로 확장해 매칭하므로 FE 는 그대로 되돌려 보내면 된다.
 * - label: 표시명 (예 "침수(범람)"). 드롭다운 표시. 이름 미등록이면 유형코드가 온다.
 * - memberCodes: 이 옵션이 매칭하는 EV-코드 목록 — 그룹 전체라 2건 이상일 수 있다.
 *
 * ★같은 표시명이 여러 번 뜨지 않는 이유가 이 그룹핑이다. 관제가 유형별 이름을 보내기 시작하면
 * 표시명이 갈라져 그룹이 자동으로 쪼개지고 옵션 수가 늘어난다(영구 병합 아님).
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
