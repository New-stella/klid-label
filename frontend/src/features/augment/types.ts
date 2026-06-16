// 증강 도메인 타입 (BE OpenAPI alias) — UI/UX §4-12 정합.
//
// 활용 결정 상태:
// - PENDING: 채택/거부 액션 노출
// - ACCEPTED: 결정 일시 표시 (변경 불가)
// - REJECTED: 거부 사유 표시 (변경 불가)

// 외부 증강 위탁 3종(WINTER/NIGHT/RAIN)만 — 해상도(RESOLUTION)는 증강이 아니라
// 저작도구가 직접 수행하는 별도 기능이므로 증강 유형에서 제외한다(CLAUDE.md SFR-06-03).
// 해상도 변경은 데이터 증강 화면(/augment)의 해상도 변경 패널에서 별도 섹션으로 제공된다.
export const AugmentType = {
  WINTER: 'WINTER',
  NIGHT: 'NIGHT',
  RAIN: 'RAIN',
} as const;
export type AugmentType = (typeof AugmentType)[keyof typeof AugmentType];

export const AugmentJobStatus = {
  REQUESTED: 'REQUESTED',
  IN_PROGRESS: 'IN_PROGRESS',
  COMPLETED: 'COMPLETED',
  FAILED: 'FAILED',
} as const;
export type AugmentJobStatus = (typeof AugmentJobStatus)[keyof typeof AugmentJobStatus];

export const AugmentDecision = {
  PENDING: 'PENDING',
  ACCEPTED: 'ACCEPTED',
  REJECTED: 'REJECTED',
} as const;
export type AugmentDecision = (typeof AugmentDecision)[keyof typeof AugmentDecision];

/** 증강 잡 카드 데이터 (이력 그리드용) */
export interface AugmentJob {
  jobId: number;
  videoId: number;
  cctvName: string;
  types: AugmentType[];
  status: AugmentJobStatus;
  requestedAt: string;
  completedAt?: string;
  videoCount: number;
}

/** 증강 결과 — 영상별 + 유형별 */
export interface AugmentResult {
  /** 결과 항목 ID (acceptAugment/rejectAugment의 path param) */
  id: number;
  videoId: number;
  cctvName: string;
  type: AugmentType;
  /** 12 프레임 페어 (원본/증강) */
  framePairs: AugmentFramePair[];
  decision: AugmentDecision;
  /** ACCEPTED 시 결정 일시 */
  decidedAt?: string;
  /** REJECTED 시 사유 */
  rejectReason?: string;
}

export interface AugmentFramePair {
  srcSn: number;
  frameNo?: number;
  originalUrl: string;
  /** 증강 처리 결과 — 실패 시 undefined */
  augmentedUrl?: string;
}

export interface AugmentResultPage {
  jobId: number;
  results: AugmentResult[];
}

export interface RequestAugmentRequest {
  videoIds: number[];
  types: AugmentType[];
}

export interface RequestAugmentResponse {
  jobId: number;
  /** 요청 일시 (ISO-8601) */
  requestedAt: string;
  /** 요청된 영상 수 */
  videoCount: number;
  /** 요청된 증강 유형 수 */
  typeCount: number;
}

/**
 * 증강 요청 실패(NOT_REVIEWED) 시 BE가 동봉하는 부가 정보.
 * `ApiError.data` 또는 응답 본문의 `data` 필드에 담겨 전달된다.
 */
export interface AugmentNotReviewedDetail {
  /** 검수 미완료로 차단된 영상 ID 목록 */
  blockedVideoIds: number[];
}

export interface ListAugmentJobsParams {
  page?: number;
  size?: number;
  /** 특정 원본 srcSn 필터 (선택) */
  srcSn?: number;
}
