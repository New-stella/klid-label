/**
 * 포털 증강 요청 조회 API (PORTAL_USER 전용).
 *
 *   GET /v1/portal/augments?page&size  → 본인이 낸 요청 목록(요청 일시 내림차순, 페이징) [API-232]
 *   GET /v1/portal/augments/{augSn}    → 요청 단건 + 결과물 위치                          [API-233]
 *
 * ★ **이 모듈에는 통신만 둔다.** 귀결 판정(`outcome.ts`)·조건 표기(`generationCondition.ts`)를
 *   여기 합치지 않는다 — 모듈 단위 모의가 그 함수들까지 지워 화면이 조용히 「모르는 값」 분기로
 *   떨어지는 함정이 이 저장소에 실재한다.
 *
 * ★★ **접수 창구(API-231)는 여기 없다 — 업로드 자산 모듈에 있다**
 *   (`features/portal/uploads/api.ts` 의 `requestUploadAugment`). 요청을 거는 자리는 이 화면이
 *   아니라 포털 업로드 화면의 자산별 액션이고(사양 SCREEN-044·SCREEN-033), 증강 화면에는 대상
 *   영상을 고르러 가는 링크만 둔다. 접수 함수를 여기 두면 같은 행위의 진입이 둘이 되는 첫
 *   단추가 된다. 창구 경로도 그쪽이 자연스럽다 — `/portal/uploads/{uldSn}/augments` 다.
 *
 * 보안: `apiClient`(baseURL `/api/v1`)가 인계 토큰을 자동 첨부하고 `ApiResponse` 를 벗긴다.
 *   경로·쿼리는 axios 가 인코딩한다(문자열 연결 금지). 남의 요청·없는 요청은 서버가 한 코드로
 *   묶어 거부하므로(존재 여부 비노출) 화면이 둘을 가르려 하지 않는다.
 */

import { apiClient } from '@/lib/api/client';
import type { PageResponse } from '@/lib/api/types';

import type { PortalAugmentDetail, PortalAugmentSummary } from './types';

export interface ListPortalAugmentsParams {
  /** 페이지 번호(0부터). 음수는 서버가 400 으로 거부한다. */
  page?: number;
  /** 페이지 크기. 서버 기본 20 이고 상한 100 은 **거부가 아니라 절단**이다. */
  size?: number;
}

/**
 * 본인이 낸 증강 요청 목록.
 *
 * ⚠ 정렬 파라미터를 보내지 않는다 — 계약이 요청 일시 내림차순으로 **고정**이며 정렬 기준을
 *   파라미터로 받지 않는다고 명시한다. 보내면 서버가 모르는 값이 되고, 화면이 정렬 수단을
 *   갖고 있다는 잘못된 인상만 남는다.
 */
export function listPortalAugments(
  params: ListPortalAugmentsParams = {},
): Promise<PageResponse<PortalAugmentSummary>> {
  return apiClient
    .get<PageResponse<PortalAugmentSummary>>('/portal/augments', { params })
    .then((r) => r.data);
}

/** 증강 요청 단건 + 결과물 위치. 본인이 낸 요청만(남의 요청·부재는 서버가 403). */
export function getPortalAugment(augSn: number): Promise<PortalAugmentDetail> {
  return apiClient
    .get<PortalAugmentDetail>(`/portal/augments/${augSn}`)
    .then((r) => r.data);
}
