import type { Role } from '../types/role';

export interface ApiResponse<T> {
  success: boolean;
  data: T;
  message?: string;
  errorCode?: string;
}

export interface Page<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

// 도메인 DTO
export interface UserDto {
  id: string;
  name: string;
  email: string;
  role: Role;
  status: 'ACTIVE' | 'INACTIVE';
  lastLoginAt: string;
}

export type BatchStatus = 'PENDING' | 'PROCESSING' | 'COMPLETED' | 'FAILED';
/**
 * 배치 파이프라인 단계 코드.
 * V1.7: VLM은 시계열 메타데이터 추출이 아닌, YOLO/SAM2가 감지한 객체의 분류 정합성을 검증하는 단계다.
 * 코드값('VLM')은 API 호환성을 위해 유지하고 표시 라벨은 stageLabel()에서 "VLM 객체 검증"으로 노출한다.
 */
export type BatchStage = 'FRAME_EXTRACT' | 'DEIDENTIFY' | 'YOLO' | 'SAM2' | 'VLM';
export type StageStatus = 'DONE' | 'PROGRESS' | 'PENDING' | 'FAIL';
export type PrivacyType = 'PRVC' | 'PSDO' | 'ANONY';

export interface VideoDto {
  id: string;
  cctvName: string;
  eventType: string;
  durationSec: number;
  recordedAt: string;
  batchStatus: BatchStatus;
  privacyType: PrivacyType;
  deidentified: boolean;
  stages: { name: BatchStage; status: StageStatus; progress: number }[];
  assigneeId?: string;
  reviewerId?: string;
  taskStatus?: 'BATCH_COMPLETED' | 'PENDING' | 'IN_PROGRESS' | 'REVIEW_PENDING' | 'REVIEW' | 'COMPLETED' | 'REJECTED';
  createdAt: string;
  updatedAt: string;
}

export interface TaskDto {
  id: string;
  videoId: string;
  videoName: string;
  status: 'PENDING' | 'IN_PROGRESS' | 'REVIEW_PENDING' | 'COMPLETED' | 'REJECTED';
  assigneeId?: string;
  assigneeName?: string;
  reviewerId?: string;
  progress: number;
  labelCount: number;
  createdAt: string;
  updatedAt: string;
}

export interface LabelObject {
  id: string;
  type: 'bbox' | 'polygon' | 'mask';
  labelCode: string;
  labelName: string;
  color: string;
  confidence: number;
  createdBy: 'auto' | 'manual';
  bbox?: { x: number; y: number; w: number; h: number };
  points?: number[];
  attributes?: Record<string, string | number>;
  trackId?: string;
}

export interface FrameLabels {
  videoId: string;
  frameNo: number;
  objects: LabelObject[];
}

export interface FrameMeta {
  frameNo: number;
  thumbnailUrl: string;
  hasIssue: boolean;
  timestampMs: number;
}

export interface ReviewDto {
  id: string;
  videoId: string;
  videoName: string;
  workerId: string;
  workerName: string;
  submittedAt: string;
  labelCount: number;
  status: 'PENDING' | 'IN_REVIEW' | 'APPROVED' | 'REJECTED';
  issues?: { frameNo: number; comment: string }[];
  rejectReason?: string;
}

export interface DeidentDto {
  id: string;
  videoId: string;
  videoName: string;
  status: 'PENDING' | 'SUCCESS' | 'FAIL' | 'N/A';
  privacyType: PrivacyType;
  processedAt?: string;
  originalUrl: string;
  deidentifiedUrl?: string;
}

export interface HistoryCommit {
  hash: string;
  videoId: string;
  message: string;
  authorName: string;
  committedAt: string;
}

/** SFR-07 — 증강 결과 학습데이터 활용 결정 상태 */
export type AugmentDecision = 'PENDING' | 'ACCEPTED' | 'REJECTED';

export interface AugmentJob {
  id: string;
  videoIds: string[];
  types: ('WINTER' | 'NIGHT' | 'RAIN' | 'RESOLUTION')[];
  status: 'PENDING' | 'PROCESSING' | 'COMPLETED' | 'FAILED';
  progress: number;
  labelIntegrity: number;
  createdAt: string;
  /** SFR-07 — 학습데이터 활용 여부. COMPLETED 잡에만 의미 있음. */
  decision?: AugmentDecision;
  /** 결정 시각 (ISO) */
  decisionAt?: string;
  /** 결정자 사용자 ID */
  decisionBy?: string;
  /** 거부 사유 (REJECTED일 때만) */
  decisionReason?: string;
}

export interface WorkerStat {
  workerId: string;
  workerName: string;
  completed: number;
  inProgress: number;
  rejected: number;
  labelCount: number;
  autoLabelRate: number;
  rejectRate: number;
}

export interface OverallStat {
  imageTarget: number;
  imageCompleted: number;
  videoTarget: number;
  videoCompleted: number;
  imageByEvent: { eventType: string; count: number }[];
  videoByEvent: { eventType: string; count: number }[];
  workers: WorkerStat[];
  dailyCounts: { date: string; count: number }[];
}

export interface PresetDto {
  id: string;
  name: string;
  description: string;
  labelCodes: string[];
  createdAt: string;
  updatedAt: string;
}

export interface PortalUserDto {
  id: string;
  name: string;
  uploadCount: number;
  labeledCount: number;
}
