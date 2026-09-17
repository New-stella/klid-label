// 검수 도메인 API — BE: /api/v1/reviews/*, /api/v1/videos/{id}/submit
//
// 보안:
// - axios가 path/body 파라미터 자동 URL 인코딩 (XSS/Injection 방어).
// - IDOR/권한 검증은 BE 책임 (REVIEWER 역할 + 본인 배정 검증).
// - 반려 사유는 zod 검증 후 전달 (max 1000자, 필수).
//
// ★일괄 승인은 별도 sub-resource(`POST /v1/reviews/batch/approve`)다 — 같은 URL 에 쿼리
// 파라미터로 행위를 분기하지 않는다(`rules/api-design.md`).
//
// [@design ADR-067] [@design API-008] [@design API-013] [@design API-014] [@design API-250]

import { apiClient } from '@/lib/api/client';
import { compactParams } from '@/lib/compactParams';

import type {
  AddIssueCommentRequest,
  AddIssueRequest,
  ApproveRequest,
  BatchApproveRequest,
  BatchApproveResponse,
  CreateInquiryRequest,
  FrameList,
  IssueComment,
  IssueThread,
  RejectRequest,
  Review,
  ReviewIssue,
  ReviewListParams,
  ReviewListResponse,
  ReviewStatus,
  ReviewStatusParam,
  ReviewSummary,
  ReviewSummaryParams,
} from './types';

/**
 * FE 상태 코드 → BE 상태 코드 **단일 역매핑 상수**.
 *
 * ★ 응답(`ReviewResponse.status`)은 FE 코드인데 요청(`GET /v1/reviews?status=`)은 BE 코드다.
 * 역매핑 없이 FE 코드를 그대로 보내면 BE 화이트리스트 밖이라 **400 이 아니라 빈 결과 200** 이
 * 돌아와 "검수요청이 하나도 없습니다" 로 위장된다(조용한 결함).
 *
 * `Record<ReviewStatus, ReviewStatusParam>` 로 선언해 **FE 상태가 늘어나면 키 누락이 컴파일
 * 에러**가 되게 한다 — 이 타입을 느슨하게 바꾸지 말 것.
 */
export const REVIEW_STATUS_TO_BE: Record<ReviewStatus, ReviewStatusParam> = {
  REVIEW_PENDING: 'PENDING',
  REVIEWING: 'IN_REVIEW',
  COMPLETED: 'APPROVED',
  REJECTED: 'REJECTED',
} as const;

/**
 * 검수 목록 조회 (REVIEWER).
 * BE: GET /api/v1/reviews — status/q 서버 필터 + sort + 페이징.
 *
 * `status` 는 여기서 **딱 한 번** BE 코드로 역매핑된다. 매핑에 없는 값(수기 URL 조작 등)은
 * 잘못된 코드를 보내 조용한 빈 결과를 만드는 대신 **필터를 생략**한다(fail-open 이 아니라
 * "필터 미적용" — 목록이 사라지지 않는 쪽이 사용자에게 정직하다).
 */
export function listReviews(params: ReviewListParams) {
  const { status, ...rest } = params;
  const query = compactParams({
    ...rest,
    status: status ? REVIEW_STATUS_TO_BE[status] : undefined,
  });
  return apiClient
    .get<ReviewListResponse>('/reviews', { params: query })
    .then((r) => r.data);
}

/**
 * 검수 일괄 승인 (REVIEWER — 관리자는 계층으로 함께 통과).
 * BE: POST /api/v1/reviews/batch/approve
 *
 * ★단건 승인 주소에 질의 항목을 붙여 행위를 가르지 않는다 — **별도 자원**이다
 * (`rules/api-design.md` 「같은 URL 에 쿼리 파라미터로 행위 분기 금지」, 선례 `/videos/batch/retry`).
 *
 * ★**한 건도 승인되지 못해도 200** 이다. 판정은 응답의 건별 결과로 하고, 전체 실패를 통신 오류로
 * 다루지 말 것. 요청 전체가 거부되는 경우(빈 목록·건수 상한 초과)만 400(`INVALID_INPUT`)이며
 * 그때는 한 건도 처리되지 않는다.
 *
 * <p>응답은 각 건의 승인이 **받아들여졌다**는 뜻이지 산출물 재생성·관제 통지가 끝났다는 뜻이
 * 아니다 — 그 연쇄는 뒤에서 비동기로 이어진다.
 */
export function approveReviewsBatch(
  body: BatchApproveRequest,
): Promise<BatchApproveResponse> {
  return apiClient
    .post<BatchApproveResponse>('/reviews/batch/approve', body)
    .then((r) => r.data);
}

/**
 * 검수 목록 KPI 집계 (REVIEWER).
 * BE: GET /api/v1/reviews/summary — **필터 결과 전체 기준** 4종 건수.
 *
 * ★ 파라미터는 `q` 뿐이다({@link ReviewSummaryParams}). `status` 를 보내면 BE 가 무시하지만,
 * 애초에 타입이 허용하지 않아 "이미 좁혀진 집합 위에서 집계" 하는 실수를 원천 차단한다.
 */
export function getReviewSummary(params: ReviewSummaryParams) {
  return apiClient
    .get<ReviewSummary>('/reviews/summary', { params: compactParams(params) })
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
 *
 * <p><b>바디는 반드시 객체로 보낸다</b>(미지정이면 빈 객체). axios 는 POST 의 Content-Type 이
 * 정해지지 않으면 {@code application/x-www-form-urlencoded} 를 기본으로 붙이는데
 * (`dispatchRequest`), 바디가 {@code null} 이면 JSON 직렬화 경로를 타지 않아 그 기본값이 그대로
 * 나간다. BE 의 {@code @RequestBody(required = false)} 는 "Content-Type 이 있는데 읽을 수 있는
 * 컨버터가 없는" 요청을 415 로 거부하고 그것이 500 으로 표면화됐다(승인 전 구간 불가).
 * 빈 객체는 {@code noLabelConfirmed=null} 이라 바디 미첨부와 동작이 동일하다.
 */
export function approveReview(id: number, body?: ApproveRequest): Promise<Review> {
  return apiClient
    .post<Review>(`/reviews/${id}/approve`, body ?? {})
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
