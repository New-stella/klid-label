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

// 통합 처리 종류 — 증강 화면(SCR-AUG-001)의 단일 선택 카드 모델.
// 증강 3종(WINTER/NIGHT/RAIN)에 더해 해상도 변경(RESOLUTION)을 같은 카드 그리드에서
// 라디오(단일 선택)로 고른다. RESOLUTION 은 증강 잡 경로가 아닌 저작도구 직접 수행
// 기능(SFR-06-03)이므로, 실행 분기는 isAugmentKind 타입가드로 좁혀 처리한다(Phase 2).
export const PROCESS_KINDS = ['WINTER', 'NIGHT', 'RAIN', 'RESOLUTION'] as const;
export type ProcessKind = (typeof PROCESS_KINDS)[number];

export const PROCESS_KIND_LABEL: Record<ProcessKind, string> = {
  WINTER: '겨울',
  NIGHT: '야간',
  RAIN: '우천',
  RESOLUTION: '해상도 변경',
};

export const PROCESS_KIND_ICON: Record<ProcessKind, string> = {
  WINTER: '❄️',
  NIGHT: '🌙',
  RAIN: '🌧',
  RESOLUTION: '🖼️',
};

export const PROCESS_KIND_DESCRIPTION: Record<ProcessKind, string> = {
  WINTER: '눈/설경 효과로 영상을 변환합니다.',
  NIGHT: '저조도 야간 환경으로 영상을 변환합니다.',
  RAIN: '강우 효과로 영상을 변환합니다.',
  RESOLUTION: '표준 하위 해상도 이미지셋으로 다운스케일합니다.',
};

/**
 * 증강(외부 위탁) 종류인지 좁히는 타입가드 — AugmentType 값 집합 기반 positive 검사.
 * 부정 조건(RESOLUTION 제외)이 아니라 화이트리스트로 판정해 PROCESS_KINDS 확장 시
 * 새 비-증강 종류가 증강으로 오분기되는 것을 막는다.
 */
const AUGMENT_KIND_SET = new Set<string>(Object.values(AugmentType));
export const isAugmentKind = (k: ProcessKind): k is AugmentType =>
  AUGMENT_KIND_SET.has(k);

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
  // 해상도 파생(SFR-06-03) 코드 목록 (BE additive 응답 필드 resolutionTypes, RESL_*).
  // 증강 위탁 잡이 아니라 저작도구 직접 수행 결과이므로 types 와 별도로 노출한다.
  // 구 응답에는 없을 수 있어 optional — 없으면 빈 목록으로 취급한다.
  resolutionTypes?: string[];
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

// 증강 결과 화면의 실제 집계 상태 — BE /{jobId}/result 응답 status 계약(3값).
// 프레임별 results 본문은 외부 SFR-07 연동 전이라 비어 있을 수 있으나, status 는 항상 실제 집계값이다.
export const AugmentResultStatus = {
  PROCESSING: 'PROCESSING',
  COMPLETED: 'COMPLETED',
  FAILED: 'FAILED',
} as const;
export type AugmentResultStatus =
  (typeof AugmentResultStatus)[keyof typeof AugmentResultStatus];

export interface AugmentResultPage {
  jobId: number;
  /**
   * BE 집계 상태(COMPLETED|FAILED|PROCESSING). 결과가 비어 있어도 이 값으로 표시하며,
   * results.length 로 상태를 파생하지 않는다(완료/실패의 "처리 중" 오표시 제거).
   */
  status: AugmentResultStatus;
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
