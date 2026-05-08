// 영상 도메인 타입 (BE OpenAPI 기반 alias)

import type { BadgeStatus } from '@/components/common/StatusBadge';

export interface VideoListParams {
  page?: number;
  size?: number;
  sort?: string;
  cctvNameKeyword?: string;
  eventTypeCd?: string;
  localGovId?: number;
  from?: string;
  to?: string;
}

export interface Video {
  id: number;
  cctvName: string;
  vmsClipId: string;
  eventName?: string;
  eventTypeCd?: string;
  localGov?: string;
  frameCount: number;
  status: BadgeStatus;
  capturedAt: string;
  thumbnailUrl?: string;
}

export interface FramePreview {
  frameNo: number;
  thumbnailUrl: string;
}

export interface VideoDetail extends Video {
  duration: number;
  fileSizeMb: number;
  resolution: string;
  framePreviews: FramePreview[];
}

// 5단계: 프레임 추출 / 비식별화 / YOLO / SAM2 / VLM 객체 검증
export type BatchStage = 'FRAME_EXTRACT' | 'DEIDENTIFY' | 'YOLO' | 'SAM2' | 'VLM_VERIFY';
export type BatchStageStatus = 'PENDING' | 'IN_PROGRESS' | 'COMPLETED' | 'FAILED';

export interface BatchStageInfo {
  stage: BatchStage;
  label: string;
  status: BatchStageStatus;
  progressPercent: number; // 0-100
}

export interface BatchVideoStatus {
  videoId: number;
  cctvName: string;
  vmsClipId: string;
  currentStage: BatchStage;
  stages: BatchStageInfo[];
  startedAt: string;
}

export interface BatchStatus {
  totalProcessing: number;
  totalCompleted: number;
  totalFailed: number;
  videos: BatchVideoStatus[];
}
