// 이벤트 코드 → 한글 라벨 해석 유틸 (순수 함수).
//
// 관제 마스터 기반 라벨 맵(useEventTypeLabels)을 주입받아 해석한다. 하드코딩 SoT 미보유.
// 훅에 의존하지 않는 순수 함수 — 컴포넌트가 훅으로 맵을 받아 주입한다.

import type { EventTypeLabelMap } from '@/features/eventType/types';

/**
 * 라벨 맵 기반 이벤트 코드 → 한글 카테고리명 변환.
 *
 * - null/undefined/빈 문자열: `'-'` 반환 (목록/카드 빈 셀 표기)
 * - 맵에 있는 코드: 카테고리 한글명 (예 EV01000102 → "침수(범람)")
 * - 미등록 코드: 입력 원문 그대로 폴백 (디버깅·과도기 안전)
 *
 * @param map  useEventTypeLabels 가 반환한 코드→라벨 맵 (로딩 중이면 undefined)
 * @param code 해석할 이벤트 코드
 */
export function labelOf(
  map: EventTypeLabelMap | undefined,
  code: string | null | undefined,
): string {
  if (!code) return '-';
  return map?.[code] ?? code;
}
