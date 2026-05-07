// 증강 도메인 타입 (BE OpenAPI alias) — UI/UX §4-12 정합.
//
// 활용 결정 상태:
// - PENDING: 채택/거부 액션 노출
// - ACCEPTED: 결정 일시 표시 (변경 불가)
// - REJECTED: 거부 사유 표시 (변경 불가)

export const AugmentType = {
  WINTER: 'WINTER',
  NIGHT: 'NIGHT',
  RAIN: 'RAIN',
  RESOLUTION: 'RESOLUTION',
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
}

export interface ListAugmentJobsParams {
  page?: number;
  size?: number;
  /** 특정 원본 srcSn 필터 (선택) */
  srcSn?: number;
}
