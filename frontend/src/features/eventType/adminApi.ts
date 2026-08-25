// 이벤트유형 관리 API — BE: /api/v1/manage/event-types (REVIEWER 전용).
//
// apiClient 응답 인터셉터가 ApiResponse<T> 래퍼를 언랩하므로 `.data` 만 추출한다.

import type { DisplayNameSource } from '@/components/common/DisplayNameSourceChip';
import { apiClient } from '@/lib/api/client';

// 연결 상태의 값 집합·판정·거르기 토큰은 <b>별도 모듈</b>이 소유한다 — 이 파일을 통째로 모의하는
// 테스트가 판정 함수까지 지워 화면이 조용히 "모르는 값" 분기로 떨어지는 것을 막기 위해서다.
// 소비처가 import 두 줄을 쓰지 않도록 타입만 여기서 다시 내보낸다(값 재수출은 하지 않는다 —
// 재수출하면 이 파일을 모의할 때 그 값이 다시 사라진다).
import type { PresetLinkStatus, PresetLinkStatusFilter } from './presetLinkStatus';

export type { PresetLinkStatus, PresetLinkStatusFilter };

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
  /**
   * 프리셋 연결 상태 — <b>목록 조회에서만 채워진다</b>.
   *
   * ⚠ 수정(PATCH) 응답에는 <b>실리지 않는다(null)</b>. 표시명을 바꾸면 표시명 그룹이 쪼개져
   * 그 유형의 대표코드가 바뀌는데, 그룹 캐시 무효화가 커밋 이후라 그 트랜잭션은 여전히
   * <b>수정 전 그룹</b>으로 판정한다 — 옛 값을 실으면 정반대로 안내하게 되므로 서버가 비운다.
   * 그래서 화면은 표시명·수집여부 저장 뒤 목록을 다시 조회한다.
   */
  presetLinkStatus?: PresetLinkStatus | null;
}

/** PATCH 요청 본문 — 허용 필드만(Mass Assignment 방어). null 은 "바꾸지 않음". */
export interface EventTypeAdminUpdate {
  /** 운영자 표시명. 빈 문자열이면 해제(관제값 복귀) */
  optrIndctNm?: string;
  /** 수집여부 Y/N */
  clctYn?: string;
}

/**
 * 등록된 이벤트유형 전체(비수집·제외 대분류 포함) — 관리 화면용.
 *
 * @param presetLinkStatus 프리셋 연결 상태 거르기 — <b>선택</b>. <b>미지정이면 파라미터 자체를
 *   싣지 않는다</b>(서버에 기본값이 없어 전체가 돌아온다). 거르기는 서버가 <b>등록된 전체</b>를
 *   대상으로 수행한다 — 이미 받아 놓은 목록을 화면에서 다시 걸러내면 아직 받지 않은 보류 유형이
 *   빠져 조치가 필요한 유형을 놓친다.
 */
export function getEventTypeAdminList(
  presetLinkStatus?: PresetLinkStatusFilter,
): Promise<EventTypeAdminItem[]> {
  return apiClient
    .get<EventTypeAdminItem[]>('/manage/event-types', {
      // undefined 면 키를 만들지 않는다 — axios 가 빈 값을 붙여 `?presetLinkStatus=` 로 나가면
      // 서버가 400(허용값 밖)으로 거부한다.
      ...(presetLinkStatus ? { params: { presetLinkStatus } } : {}),
    })
    .then((r) => r.data);
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
