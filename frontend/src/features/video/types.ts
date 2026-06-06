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
  // 검수 상태 필터 (LS_RAW_DATA_STATUS) — APPROVED 만 노출하는 증강 요청 화면용
  reviewStatusCd?: string;
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
  // 영상별 최근 내보내기 상태 (BE: LS_RAW_DATA_STATUS join LS_DATA_SET 최신 1건)
  exportStatus?: 'EXPORTED' | 'FAILED' | null;
  exportedAt?: string | null;
  lastExportFailureReason?: string | null;
  // 영상 마지막 수정 시각 (LS_DATA_RAW.UPD_DT)
  updatedAt?: string | null;
  // 검수 완료 시각 (LS_RAW_DATA_STATUS.UPD_DT) — 검수 상태가 APPROVED 일 때만, 그 외 null
  reviewCompletedAt?: string | null;
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

// SFR-06-03 — 해상도 export (저작도구 직접 수행, 증강 아님).
// 표준 하위 해상도 화이트리스트 (BE ResolutionPreset enum 과 1:1).
export const RESOLUTION_PRESETS = ['RES_1080P', 'RES_720P', 'RES_480P'] as const;
export type ResolutionPreset = (typeof RESOLUTION_PRESETS)[number];

export const RESOLUTION_PRESET_LABEL: Record<ResolutionPreset, string> = {
  RES_1080P: '1080P (1920×1080)',
  RES_720P: '720P (1280×720)',
  RES_480P: '480P (854×480)',
};

// BE: ResolutionChangeResponse (POST /v1/videos/{rawSn}/resolution)
export interface ResolutionExportResult {
  exportSn: number;
  srcW: number;
  srcH: number;
  targetW: number;
  targetH: number;
  frameCount: number;
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
