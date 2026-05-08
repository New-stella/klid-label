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

// BE: BatchStageProgress (GET /v1/batch/status → { items: [...] })
export interface BatchStageProgress {
  rawSn: number;
  stage: string; // PENDING | FRAME_EXTRACT | DEIDENTIFY | YOLO | SAM2 | VLM_VERIFY | COMPLETED | FAILED
  startedAt: string | null;
  lastUpdatedAt: string | null;
  retryCount: number;
  errorMessage: string | null;
}

export interface BatchStatus {
  items: BatchStageProgress[];
}
