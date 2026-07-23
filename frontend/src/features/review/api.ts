// 검수 도메인 API — BE: /api/v1/reviews/*, /api/v1/videos/{id}/submit
//
// 보안:
// - axios가 path/body 파라미터 자동 URL 인코딩 (XSS/Injection 방어).
// - IDOR/권한 검증은 BE 책임 (REVIEWER 역할 + 본인 배정 검증).
// - 반려 사유는 zod 검증 후 전달 (max 1000자, 필수).

import { apiClient } from '@/lib/api/client';
import type { PageResponse } from '@/lib/api/types';

import type {
  AddIssueCommentRequest,
  AddIssueRequest,
  ApproveRequest,
  CreateInquiryRequest,
  FrameList,
  IssueComment,
  IssueThread,
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
 * BE: POST /api/v1/reviews/{videoId}/submit
 * 상태 전이: ASSIGNED/REJECTED → PENDING
 */
export function submitReview(videoId: number): Promise<Review> {
  return apiClient.post<Review>(`/reviews/${videoId}/submit`).then((r) => r.data);
}

/**
 * 작업자가 검수 제출을 취소 (검수 시작 전에만 가능).
 * BE: POST /api/v1/reviews/{videoId}/cancel-submit
 * 상태 전이: REVIEW_PENDING(PENDING) → ASSIGNED
 *
 * 검수 시작(REVIEWING)/승인/반려 후에는 BE 가 거부(400/409). 본인 배정 검증은 BE 책임(IDOR).
 */
export function cancelSubmitReview(videoId: number): Promise<Review> {
  return apiClient
    .post<Review>(`/reviews/${videoId}/cancel-submit`)
    .then((r) => r.data);
}

/**
 * BE IssueResponse 원본 shape — FE ReviewIssue 와 필드명이 다르므로 변환 매퍼 적용.
 *
 * BE: { dataIssueSn, upDataIssueSn, videoId, issueReason, reportedUserNo, registeredAt }
 * FE: { id,         frameId,        description, createdAt }
 *
 * LS_DATA_ISSUE 는 영상 단위 반려 (좌표/프레임 컬럼 없음 — 설계서 §5A.6) → frameId 는 videoId 로 대체.
 * 추후 BE 가 프레임 단위 이슈 컬럼을 도입하면 매퍼에서 frameNo 로 교체한다.
 */
interface BackendIssueResponse {
  dataIssueSn: number;
  upDataIssueSn: number | null;
  videoId: number;
  issueReason: string;
  reportedUserNo: string | null;
  registeredAt: string;
  // 호환 — BE 가 FE 호환 shape 으로 응답하는 경로(테스트 mock 등)도 허용.
  id?: number;
  frameId?: number;
  description?: string;
  createdAt?: string;
}

function toReviewIssue(raw: BackendIssueResponse): ReviewIssue {
  return {
    id: raw.id ?? raw.dataIssueSn,
    frameId: raw.frameId ?? raw.videoId,
    description: raw.description ?? raw.issueReason ?? '',
    createdAt: raw.createdAt ?? raw.registeredAt ?? '',
  };
}

/**
 * 검수 이슈 목록.
 * BE: GET /api/v1/reviews/{id}/issues
 *
 * BE 원본 IssueResponse → FE ReviewIssue 매핑 적용 (필드명 정합).
 */
export function listIssues(reviewId: number): Promise<ReviewIssue[]> {
  return apiClient
    .get<BackendIssueResponse[]>(`/reviews/${reviewId}/issues`)
    .then((r) => (r.data ?? []).map(toReviewIssue));
}

/**
 * 이슈 추가 (프레임 단위 카드 누적).
 * BE: POST /api/v1/reviews/{id}/issues
 *
 * BE 원본 IssueResponse → FE ReviewIssue 매핑 적용 (필드명 정합).
 */
export function addIssue(reviewId: number, body: AddIssueRequest): Promise<ReviewIssue> {
  return apiClient
    .post<BackendIssueResponse>(`/reviews/${reviewId}/issues`, body)
    .then((r) => toReviewIssue(r.data));
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

// ─────────────────────────────────────────────────────────────────
// Phase 2 — 이슈 스레드 (반려 이력 + 문의 통합 소통 채널)
//
// 보안:
// - rawSn/issueSn/srcSn path·body 파라미터는 axios 가 URL 인코딩 (Injection 방어).
// - content 는 zod 검증 후 전달 (1~1000자). XSS 저장 방어는 BE, 렌더 escape 는 FE(텍스트 노드).
// - IDOR/권한(REVIEWER 해소·WORKER 문의)·낙관적 잠금은 BE 책임.
// ─────────────────────────────────────────────────────────────────

/**
 * 영상 단위 이슈 스레드 목록.
 * BE: GET /api/v1/videos/{rawSn}/issues → IssueThread[] (REG_DT asc).
 * 반려(REJECTION) 이력과 문의(INQUIRY) 가 통합 조회된다.
 */
export function listIssueThreads(rawSn: number): Promise<IssueThread[]> {
  return apiClient
    .get<IssueThread[]>(`/videos/${rawSn}/issues`)
    .then((r) => r.data ?? []);
}

/**
 * 문의(INQUIRY) 등록 — WORKER.
 * BE: POST /api/v1/videos/{rawSn}/issues → 201 IssueThread.
 */
export function createInquiry(
  rawSn: number,
  body: CreateInquiryRequest,
): Promise<IssueThread> {
  return apiClient
    .post<IssueThread>(`/videos/${rawSn}/issues`, body)
    .then((r) => r.data);
}

/**
 * 이슈 스레드 댓글 추가 — REVIEWER/WORKER.
 * BE: POST /api/v1/issues/{issueSn}/comments → 201 IssueComment.
 */
export function addIssueComment(
  issueSn: number,
  body: AddIssueCommentRequest,
): Promise<IssueComment> {
  return apiClient
    .post<IssueComment>(`/issues/${issueSn}/comments`, body)
    .then((r) => r.data);
}

/**
 * 문의 해소 처리 — REVIEWER 전용.
 * BE: POST /api/v1/issues/{issueSn}/resolve → 200.
 */
export function resolveIssue(issueSn: number): Promise<void> {
  return apiClient.post<void>(`/issues/${issueSn}/resolve`).then(() => undefined);
}
