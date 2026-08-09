// 증강 도메인 API — BE: /api/v1/augments/*.
//
// 보안:
// - axios가 path/body 파라미터 자동 URL 인코딩 (XSS/Injection 방어).
// - IDOR/권한 검증은 BE 책임 (REVIEWER 역할).
// - 반려 사유는 zod 검증 후 전달 (max 500자, 필수) — RejectReasonModal에서 처리.
// - genType allowlist (WINTER/NIGHT/RAIN)는 RequestAugmentRequest 타입으로 강제.
//   해상도(RESOLUTION)는 증강 위탁 대상이 아니라 저작도구 직접 수행이므로 제외(SFR-06-03).

import { apiClient } from '@/lib/api/client';
import type { PageResponse } from '@/lib/api/types';

import type {
  AugmentCancelResult,
  AugmentJob,
  AugmentProgress,
  AugmentResult,
  AugmentResultPage,
  CancelAugmentRequest,
  GetAugmentResultParams,
  ListAugmentJobsParams,
  RequestAugmentRequest,
  RequestAugmentResponse,
} from './types';

/**
 * 증강 요청 생성.
 * BE: POST /api/v1/augments/request
 *
 * 실패 응답:
 * - 400 errorCode=NOT_REVIEWED : data.blockedVideoIds 에 차단된 영상 ID 목록
 *   (응답 처리는 `AugmentNotReviewedDetail` 타입 참고).
 */
export function requestAugment(body: RequestAugmentRequest): Promise<RequestAugmentResponse> {
  return apiClient
    .post<RequestAugmentResponse>('/augments/request', body)
    .then((r) => r.data);
}

/**
 * 증강 잡 이력 조회 (5초 폴링 대상).
 * BE: GET /api/v1/augments?srcSn=
 */
export function listAugmentJobs(
  params: ListAugmentJobsParams = {},
): Promise<PageResponse<AugmentJob>> {
  return apiClient
    .get<PageResponse<AugmentJob>>('/augments', { params })
    .then((r) => r.data);
}

/**
 * 증강 결과 단건 조회 (영상별/항목별 결과 묶음).
 * BE: GET /api/v1/augments/{jobId}/result?page=&size=&itemPage=&itemSize=
 *
 * 페이징 축이 둘이다 — `page`/`size` 는 **프레임 쌍**(BE 기본 0/12),
 * `itemPage`/`itemSize` 는 **결과 항목**(BE 기본 0/20). 미지정 시 BE 기본값을 따른다.
 */
export function getAugmentResult(
  jobId: number,
  params: GetAugmentResultParams = {},
): Promise<AugmentResultPage> {
  return apiClient
    .get<AugmentResultPage>(`/augments/${jobId}/result`, { params })
    .then((r) => r.data);
}

/**
 * 증강 결과 채택 (PENDING → ACCEPTED).
 * BE: POST /api/v1/augments/{id}/accept
 */
export function acceptAugment(id: number): Promise<AugmentResult> {
  return apiClient
    .post<AugmentResult>(`/augments/${id}/accept`)
    .then((r) => r.data);
}

/**
 * 증강 결과 반려 (PENDING → REJECTED). 반려 사유 필수.
 * BE: POST /api/v1/augments/{id}/reject
 */
export function rejectAugment(id: number, reason: string): Promise<AugmentResult> {
  return apiClient
    .post<AugmentResult>(`/augments/${id}/reject`, { reason })
    .then((r) => r.data);
}

/**
 * 폐기(반려)된 증강 파생영상 **복구** (REJECTED → 활용 결정 대기). 사유 필수.
 * BE: POST /api/v1/augments/{id}/restore
 *
 * 복구는 표식 해제에 그치지 않고 **반려 자체를 되돌린다** — 검수가 재오픈되어 다시 채택/반려를
 * 고를 수 있다. 응답 본문(요약)은 화면이 쓰지 않는다 — 갱신은 결과 쿼리 무효화 후 **재조회**가
 * 진실이다(BE 가 락을 잡고 재판정하므로 응답을 낙관적으로 반영하면 실제 상태와 어긋난다).
 *
 * 바디는 **`reason` 하나만** 보낸다(Mass Assignment, CWE-915). 응답 본문은 쓰지 않으므로
 * 형태를 선언하지 않는다 — 쓰지도 않을 타입을 선언하면 BE 계약(`AugmentSummaryResponse`)이
 * 바뀌어도 아무도 눈치채지 못한 채 거짓 타입만 남는다.
 */
export function restoreAugment(id: number, reason: string): Promise<void> {
  return apiClient
    .post(`/augments/${id}/restore`, { reason })
    .then(() => undefined);
}

/**
 * 증강 진행상태 조회 (폴링 대상).
 * BE: GET /api/v1/augments/{id}/progress — id 는 결과 항목 id(= DATA_AUG_SN).
 *
 * 외부 조회 실패는 500 이 아니라 200 + `progress=null` + `unavailableReason` 으로 degrade 된다.
 * 해상도 파생(RESL_*)은 외부 위탁이 없어 400 이므로 **호출하지 않는다**(호출부에서 차단).
 */
export function getAugmentProgress(id: number): Promise<AugmentProgress> {
  return apiClient
    .get<AugmentProgress>(`/augments/${id}/progress`)
    .then((r) => r.data);
}

/**
 * 증강 요청 취소 (REVIEWER).
 * BE: POST /api/v1/augments/{id}/cancel
 *
 * 바디는 **`reason` 하나만** 보낸다 — 종결 판정을 요청으로 조작할 수 있는 필드를 두지 않는다
 * (Mass Assignment, CWE-915). 사유가 비면 바디 없이 보낸다(사유 없는 취소가 정상 동선).
 * 응답은 부분 취소를 표현하는 전용 타입이라 accept/reject 와 같은 파서로 다루면 안 된다.
 */
export function cancelAugment(
  id: number,
  reason?: string,
): Promise<AugmentCancelResult> {
  const trimmed = reason?.trim();
  const body: CancelAugmentRequest | undefined = trimmed
    ? { reason: trimmed }
    : undefined;
  return apiClient
    .post<AugmentCancelResult>(`/augments/${id}/cancel`, body)
    .then((r) => r.data);
}
