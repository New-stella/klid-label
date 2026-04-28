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
  role: 'ADMIN' | 'REVIEWER' | 'WORKER' | 'PORTAL_USER';
  status: 'ACTIVE' | 'INACTIVE';
  lastLoginAt: string;
}

export type BatchStatus = 'PENDING' | 'PROCESSING' | 'COMPLETED' | 'FAILED';
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
  downloadDeadline?: string; // ISO datetime — 포털 업로드 영상에만 부여, 영상별 다운로드 만료일
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

export interface MartDataset {
  id: string;
  name: string;
  eventType: string;
  weather: string;
  season: string;  // '봄' | '여름' | '가을' | '겨울'
  count: number;
  version: string;
  sizeBytes: number;
  createdAt: string;
  /** SFR-13 — 활용 AI 모델 코드 배열 (침수탐지/이상상황탐지 등 자체개발 모델). 비어있으면 연동 없음. */
  linkedModels?: string[];
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
  /** @deprecated 사용자 단일 기한은 폐기. 다운로드 기한은 VideoDto.downloadDeadline(영상별)을 사용하세요. */
  downloadDeadline: string;
}
