// 검수 도메인 API — BE: /api/v1/reviews/*, /api/v1/videos/{id}/submit
//
// 보안:
// - axios가 path/body 파라미터 자동 URL 인코딩 (XSS/Injection 방어).
// - IDOR/권한 검증은 BE 책임 (REVIEWER 역할 + 본인 배정 검증).
// - 반려 사유는 zod 검증 후 전달 (max 1000자, 필수).

import { apiClient } from '@/lib/api/client';
import type { PageResponse } from '@/lib/api/types';

import type {
  AddIssueRequest,
  ApproveRequest,
  FrameList,
  RejectRequest,
  Review,
  ReviewIssue,
  ReviewListParams,
} from './types';

/**
 * 검수 대기 목록 조회.
 * BE: GET /api/v1/reviews
 */
export function listReviews(params: ReviewListParams) {
  return apiClient
    .get<PageResponse<Review>>('/reviews', { params })
    .then((r) => r.data);
}

/**
 * 검수 단건 상세.
 * BE: GET /api/v1/reviews/{id}
 */
export function getReview(id: number): Promise<Review> {
  return apiClient.get<Review>(`/reviews/${id}`).then((r) => r.data);
}

/**
 * 검수 시작.
 * BE: POST /api/v1/reviews/{id}/start
 * 상태 전이: REVIEW_PENDING → REVIEWING
 */
export function startReview(id: number): Promise<Review> {
  return apiClient.post<Review>(`/reviews/${id}/start`).then((r) => r.data);
}

/**
 * 검수 승인.
 * BE: POST /api/v1/reviews/{id}/approve
 * 상태 전이: REVIEWING → COMPLETED
 */
export function approveReview(id: number, body?: ApproveRequest): Promise<Review> {
  return apiClient
    .post<Review>(`/reviews/${id}/approve`, body ?? null)
    .then((r) => r.data);
}

/**
 * 검수 반려.
 * BE: POST /api/v1/reviews/{id}/reject
 * 상태 전이: REVIEWING → REJECTED → 작업 IN_PROGRESS 복귀 (BE에서 처리)
 *
 * 반려 사유는 텍스트 필수 (UI/UX §4-9).
 */
export function rejectReview(id: number, body: RejectRequest): Promise<Review> {
  return apiClient.post<Review>(`/reviews/${id}/reject`, body).then((r) => r.data);
}

/**
 * 작업자가 라벨링 결과를 검수 제출.
 * BE: POST /api/v1/videos/{videoId}/submit
 * 상태 전이: IN_PROGRESS → REVIEW_PENDING
 */
export function submitReview(videoId: number): Promise<Review> {
  return apiClient.post<Review>(`/videos/${videoId}/submit`).then((r) => r.data);
}

/**
 * 검수 이슈 목록.
 * BE: GET /api/v1/reviews/{id}/issues
 */
export function listIssues(reviewId: number): Promise<ReviewIssue[]> {
  return apiClient
    .get<ReviewIssue[]>(`/reviews/${reviewId}/issues`)
    .then((r) => r.data);
}

/**
 * 이슈 추가 (프레임 단위 카드 누적).
 * BE: POST /api/v1/reviews/{id}/issues
 */
export function addIssue(reviewId: number, body: AddIssueRequest): Promise<ReviewIssue> {
  return apiClient
    .post<ReviewIssue>(`/reviews/${reviewId}/issues`, body)
    .then((r) => r.data);
}

/**
 * 검수 화면 프레임 목록 + 라벨 조회.
 * BE: GET /api/v1/reviews/{videoId}/frames (Phase 1 신설)
 *
 * 보안:
 * - videoId path 파라미터는 axios가 URL 인코딩 (XSS/Injection 방어).
 * - 본인 배정 검증은 BE 책임 (IDOR 방어).
 */
export function getReviewFrames(videoId: number): Promise<FrameList> {
  return apiClient
    .get<FrameList>(`/reviews/${videoId}/frames`)
    .then((r) => r.data);
}
