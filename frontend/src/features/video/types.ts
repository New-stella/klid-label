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
  dataSttsCd?: string;
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
  privacyTypeCd?: string;
  durationSec?: number;
  // 영상별 최근 내보내기 상태 (BE: LS_PJT_DATA_STTS join LS_DATA_SET 최신 1건)
  exportStatus?: 'EXPORTED' | 'FAILED' | null;
  exportedAt?: string | null;
  lastExportFailureReason?: string | null;
}

export interface FramePreview {
  srcSn: number;
  frameNo: number;
  thumbnailUrl: string;
  timestampMs?: number;
  hasIssue?: boolean;
}

export type BatchStageStatus = 'DONE' | 'PROGRESS' | 'PENDING' | 'FAIL';

export interface BatchStageItem {
  name: string; // 'FRAME_EXTRACT' | 'DEIDENTIFY' | 'YOLO' | 'SAM2' | 'VLM_VERIFY'
  status: BatchStageStatus;
  progress: number;
}

export interface VideoDetail extends Video {
  duration: number;
  fileSizeMb: number;
  resolution: string;
  framePreviews: FramePreview[];
  stages?: BatchStageItem[];
  createdAt?: string;
  updatedAt?: string;
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

export interface LabelObject {
  id: string;
  labelCode: string;
  labelName: string;
  color: string;
  confidence: number; // 0~1
  createdBy?: 'auto' | 'manual';
}

export interface FrameLabels {
  videoId: string | number;
  objects: LabelObject[];
}
