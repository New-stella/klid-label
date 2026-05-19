// 증강 도메인 API — BE: /api/v1/augments/*.
//
// 보안:
// - axios가 path/body 파라미터 자동 URL 인코딩 (XSS/Injection 방어).
// - IDOR/권한 검증은 BE 책임 (REVIEWER 역할).
// - 거부 사유는 zod 검증 후 전달 (max 500자, 필수) — RejectReasonModal에서 처리.
// - genType allowlist (WINTER/NIGHT/RAIN/RESOLUTION)는 RequestAugmentRequest 타입으로 강제.

import { apiClient } from '@/lib/api/client';
import type { PageResponse } from '@/lib/api/types';

import type {
  AugmentJob,
  AugmentResult,
  AugmentResultPage,
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
 * 증강 결과 단건 조회 (영상별/유형별 결과 묶음).
 * BE: GET /api/v1/augments/{jobId}/result
 */
export function getAugmentResult(jobId: number): Promise<AugmentResultPage> {
  return apiClient
    .get<AugmentResultPage>(`/augments/${jobId}/result`)
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
 * 증강 결과 거부 (PENDING → REJECTED). 거부 사유 필수.
 * BE: POST /api/v1/augments/{id}/reject
 */
export function rejectAugment(id: number, reason: string): Promise<AugmentResult> {
  return apiClient
    .post<AugmentResult>(`/augments/${id}/reject`, { reason })
    .then((r) => r.data);
}
