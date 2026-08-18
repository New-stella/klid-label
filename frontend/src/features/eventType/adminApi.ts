// 이벤트유형 관리 API — BE: /api/v1/manage/event-types (REVIEWER 전용).
//
// apiClient 응답 인터셉터가 ApiResponse<T> 래퍼를 언랩하므로 `.data` 만 추출한다.

import type { DisplayNameSource } from '@/components/common/DisplayNameSourceChip';
import { apiClient } from '@/lib/api/client';

/**
 * 관리 화면용 이벤트유형 1건 — BE `EventTypeAdminResponse` 1:1 미러.
 *
 * 표시명은 BE 가 4단 폴백(운영자 표시명 → 관제 수신명 → 카테고리명 → 유형코드)으로 계산해
 * `dsplNm` 으로 내려준다. ★FE 에서 폴백을 다시 계산하지 말 것 — 판정이 갈라진다.
 */
export interface EventTypeAdminItem {
  /** 유형코드(PK, 수정 불가) */
  evntTypeCd: string;
  /** 표시명 — BE 폴백 해석 결과 */
  dsplNm: string;
  /**
   * 표시명이 <b>어느 단계에서 왔는지</b> — BE `EventTypeDisplayNamePolicy.Source.wireValue()`.
   *
   * ★화면은 이 값을 그대로 표기한다(출처 칩). optrIndctNm/evntNm/evntCtgryNm 을 보고 폴백을
   * 다시 판정하지 말 것 — 판정이 두 곳으로 갈리면 정책이 바뀔 때 한쪽만 낡는다.
   * optional 로 두지 않는다 — 미수신을 특정 단계로 오인시키지 않기 위해서다.
   */
  dsplNmSource: DisplayNameSource;
  /** 운영자 표시명 — 수정 가능. 비우면 관제 수신명으로 복귀 */
  optrIndctNm?: string | null;
  /** 관제 수신 유형명 — 읽기 전용("관제 원본") */
  evntNm?: string | null;
  /** 카테고리명 — 읽기 전용. 유형에 고유 이름이 없을 때 표시명의 출처 */
  evntCtgryNm?: string | null;
  /** 대분류코드 — 관제 수신값(읽기 전용) */
  evntClsfCd?: string | null;
  /** 카테고리코드 — 관제 수신값(읽기 전용) */
  evntCtgryCd?: string | null;
  /** 수집여부 Y/N — 필터 드롭다운 노출 토글 */
  clctYn: string;
  regDt?: string | null;
}

/** PATCH 요청 본문 — 허용 필드만(Mass Assignment 방어). null 은 "바꾸지 않음". */
export interface EventTypeAdminUpdate {
  /** 운영자 표시명. 빈 문자열이면 해제(관제값 복귀) */
  optrIndctNm?: string;
  /** 수집여부 Y/N */
  clctYn?: string;
}

/** 등록된 이벤트유형 전체(비수집·제외 대분류 포함) — 관리 화면용. */
export function getEventTypeAdminList(): Promise<EventTypeAdminItem[]> {
  return apiClient.get<EventTypeAdminItem[]>('/manage/event-types').then((r) => r.data);
}

/** 이벤트유형 부분 수정(PATCH) — 200 + 수정된 데이터. */
export function updateEventTypeAdmin(
  evntTypeCd: string,
  body: EventTypeAdminUpdate,
): Promise<EventTypeAdminItem> {
  return apiClient
    .patch<EventTypeAdminItem>(`/manage/event-types/${encodeURIComponent(evntTypeCd)}`, body)
    .then((r) => r.data);
}
