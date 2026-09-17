/**
 * 포털 소재 조달 API (PORTAL_USER 전용).
 *
 *   POST /v1/portal/datasets/{datasetId}/materials → 조달 착수. 언제나 <b>202(접수)</b>.
 *   GET  /v1/portal/datasets/{datasetId}/materials → 조달 상태 + 해제본 요약.
 *
 * <h3>★ 착수는 멱등이다 — 착수와 조회가 같은 모양을 돌려준다</h3>
 * 이미 준비 완료거나 진행 중이면 서버가 <b>새로 시작하지 않고</b> 그 상태를 그대로 답한다. 그래서
 * 착수 응답도 조회 응답과 같은 타입이고, 화면은 착수 결과를 그대로 캐시에 앉혀 쓸 수 있다.
 * ⚠ <b>상태 구분을 응답 코드로 하지 말 것</b> — 어느 경우든 202 다. 구분은 본문의 상태 값이 싣는다.
 *
 * <h3>거부</h3>
 * 큐가 차면 <b>503</b> 이고 서버가 안내 문장을 함께 준다(그 문장을 화면이 그대로 보여 준다).
 * 식별자가 양수가 아니면 400 이지만, 화면은 그 값을 부르기 전에 스스로 거른다.
 *
 * ★ <b>이 모듈에는 통신만 둔다.</b> 사유 표기(`failureReason.ts`)·폴링 판정(`polling.ts`)을 여기
 *   합치지 않는다 — 모듈 단위 모의가 그 함수들까지 지워 화면이 조용히 「모르는 값」 분기로
 *   떨어지는 함정이 이 저장소에 실재한다.
 *
 * 보안: `apiClient`(baseURL `/api/v1`)가 인계 토큰을 자동 첨부하고 `ApiResponse` 를 벗긴다.
 *   경로는 axios 가 인코딩한다(문자열 연결 금지). 남의 데이터셋 차단은 서버 몫이다.
 *
 * @design INT-014
 */

import { apiClient } from '@/lib/api/client';

import type {
  PortalDatasetRegistrationResult,
  PortalDatasetVideoPage,
  PortalMaterialsStatus,
} from './types';

/** 조달 상태 조회. */
export function getDatasetMaterials(datasetId: number): Promise<PortalMaterialsStatus> {
  return apiClient
    .get<PortalMaterialsStatus>(`/portal/datasets/${datasetId}/materials`)
    .then((r) => r.data);
}

/**
 * 조달 착수 — 접수까지다(202). 진행은 상태 조회로 본다.
 *
 * ⚠ 본문을 보내지 않는다 — 창구가 경로 변수 하나만 받는다.
 */
export function startDatasetMaterials(datasetId: number): Promise<PortalMaterialsStatus> {
  return apiClient
    .post<PortalMaterialsStatus>(`/portal/datasets/${datasetId}/materials`)
    .then((r) => r.data);
}

/**
 * 데이터셋 영상 목록 — 소재가 준비된 뒤 영상을 골라 라벨링으로 들어가는 목록. @design API-253
 *
 * ⚠ 소재가 준비되지 않았으면 서버가 409 로 거부한다. 화면은 상태 조회로 준비 완료를 확인한 뒤에만
 *   이 창구를 부른다.
 */
export function getDatasetVideos(
  datasetId: number,
  params: { page: number; size: number },
): Promise<PortalDatasetVideoPage> {
  return apiClient
    .get<PortalDatasetVideoPage>(`/portal/datasets/${datasetId}/videos`, { params })
    .then((r) => r.data);
}

/**
 * 데이터셋 영상 등록 재착수 — 실패 표식일 때만 등록을 다시 시작시킨다. @design API-262
 *
 * ⚠ 본문을 보내지 않는다 — 창구가 경로 변수 하나만 받는다.
 * ⚠ 응답 코드는 언제나 200 이다(접수·이미 완료·진행 중 모두). 구분은 본문의 상태 값이 싣는다 —
 *   조달 착수(202)와 다르다. 거부는 409(소재 미준비·등록 꺼짐)·503(대기열 포화)이며 서버 안내
 *   문장이 `ApiError` 로 온다.
 */
export function restartDatasetRegistration(
  datasetId: number,
): Promise<PortalDatasetRegistrationResult> {
  return apiClient
    .post<PortalDatasetRegistrationResult>(`/portal/datasets/${datasetId}/registration`)
    .then((r) => r.data);
}
