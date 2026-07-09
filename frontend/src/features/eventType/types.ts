// 이벤트 타입 도메인 타입 — BE: GET /api/v1/event-types, /event-types/labels 정합 (Phase 2/4a).
//
// 관제 마스터 코드 기반. 표시는 카테고리 9종(상세 EV-코드를 카테고리로 묶음).

/**
 * 이벤트 타입 필터 옵션 — BE `EventTypeResponse` 1:1 미러.
 *
 * - categoryKey: 카테고리 키 = EVNT_CLS_CD + EVNT_CTGRY_CD (예 "010001"=침수(범람)). 드롭다운 value.
 * - label: 카테고리 한글명 (예 "침수(범람)"). 드롭다운 표시.
 * - memberCodes: 이 카테고리에 속한 수집대상 상세 EV-코드 목록 (예 EV01000101/102/103).
 */
export interface EventTypeOption {
  categoryKey: string;
  label: string;
  memberCodes: string[];
}

/**
 * 이벤트 코드 → 한글 카테고리명 맵 — BE `/event-types/labels` 응답.
 *
 * 전체 EV-코드(비수집 EV07000201→"기타 상황" 포함)를 카테고리 한글명으로 해석한다.
 * 미등록 코드는 맵에 없으므로 소비측에서 원문 폴백한다.
 */
export type EventTypeLabelMap = Record<string, string>;
