// 비식별화 결과 도메인 타입.

export const PrvcType = {
  PRVC: 'PRVC', // 사생활 보호 (비식별 처리 대상)
  PSDO: 'PSDO', // 의사 비식별 (비식별 처리 대상)
  ANONY: 'ANONY', // 이미 익명 (원본만 저장)
} as const;
export type PrvcType = (typeof PrvcType)[keyof typeof PrvcType];

export const DeidentStatus = {
  PENDING: 'PENDING',
  IN_PROGRESS: 'IN_PROGRESS',
  COMPLETED: 'COMPLETED',
  FAILED: 'FAILED',
} as const;
export type DeidentStatus = (typeof DeidentStatus)[keyof typeof DeidentStatus];

export interface DeidentListRow {
  videoId: number;
  cctvName: string;
  vmsClipId: string;
  prvcType: PrvcType;
  /** 'Y'/'N' — N이면 비식별 행위 미적용 (액션 미노출) */
  prvcYn: 'Y' | 'N';
  status: DeidentStatus;
  totalFrames: number;
  processedFrames: number;
  failedFrames: number;
  capturedAt: string; // ISO8601
}

export interface DeidentListParams {
  page?: number;
  size?: number;
  prvcYn?: 'Y' | 'N' | '';
  status?: DeidentStatus | '';
}

export interface DeidentFramePair {
  srcSn: number;
  frameNo: number;
  /** 항상 존재 (원본) */
  originalUrl: string;
  /** 비식별 실패/대상 외인 경우 undefined → "비식별 이미지 없음" */
  processedUrl?: string;
}

export interface DeidentProcessHistoryItem {
  attemptNo: number;
  attemptedAt: string; // ISO8601
  status: DeidentStatus;
  message?: string;
  durationMs?: number;
}

export interface DeidentDetail {
  videoId: number;
  cctvName: string;
  vmsClipId: string;
  prvcType: PrvcType;
  prvcYn: 'Y' | 'N';
  status: DeidentStatus;
  totalFrames: number;
  processedFrames: number;
  failedFrames: number;
  /** 12 frame 페어 (UI/UX §4-10 12 그리드) */
  framePairs: DeidentFramePair[];
  history: DeidentProcessHistoryItem[];
}
