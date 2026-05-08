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
 * V1.8: VLM이 시계열 메타 단계로 맨 앞에 배치된다(외부 시스템 호출). 비식별 처리는 stage에서 제거되어
 * 내보내기 시 옵션으로 호출된다.
 */
export type BatchStage = 'VLM' | 'FRAME_EXTRACT' | 'YOLO' | 'SAM2';
export type StageStatus = 'DONE' | 'PROGRESS' | 'PENDING' | 'FAIL';
export type PrivacyType = 'PRVC' | 'PSDO' | 'ANONY';

/** 포털 전송(내보내기) 이력 상태 — NEVER: 한 번도 전송 안 됨, EXPORTED: 전송 성공, FAILED: 이전 전송 시도 실패 */
export type ExportStatus = 'NEVER' | 'EXPORTED' | 'FAILED';

export interface VideoDto {
  id: string;
  cctvName: string;
  eventType: string;
  durationSec: number;
  recordedAt: string;
  batchStatus: BatchStatus;
  privacyType: PrivacyType;
  stages: { name: BatchStage; status: StageStatus; progress: number }[];
  assigneeId?: string;
  reviewerId?: string;
  taskStatus?: 'BATCH_COMPLETED' | 'PENDING' | 'IN_PROGRESS' | 'REVIEW_PENDING' | 'REVIEW' | 'COMPLETED' | 'REJECTED';
  /** 포털 전송 이력 — 기본 NEVER. EXPORTED인 영상은 강제 재전송하지 않는 한 다시 보내지 않는다. */
  exportStatus?: ExportStatus;
  /** 마지막 포털 전송 성공 시각 (ISO) — exportStatus가 EXPORTED일 때만 의미 있음 */
  exportedAt?: string;
  /**
   * 마지막 포털 전송 시도 실패 사유 — exportStatus가 FAILED일 때만 의미 있음.
   * 예: "비식별 실패", "포털 응답 오류". 비식별은 매 전송 시도마다 실행되는 단발 행위이므로
   * 영상이 영구적으로 "비식별 실패" 상태를 갖지는 않으며, 다음 시도에서 다시 시도 가능하다.
   */
  lastExportFailureReason?: string;
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

/** 일괄 배정 요청 — 여러 영상에 동일 작업자/검수자를 배정한다. 멱등 처리. */
export interface BulkAssignRequest {
  videoIds: string[];
  assigneeId: string;
  assigneeName?: string;
  reviewerId?: string;
}

/** 일괄 배정 응답 — 처리 결과 카운트. assigned는 신규/갱신, skipped는 변경 없음(이미 동일 배정), total은 요청 건수. */
export interface BulkAssignResponse {
  assigned: number;
  skipped: number;
  total: number;
  tasks: TaskDto[];
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

/** 내보내기 포맷 — 학습데이터셋 출력 형식 */
export type ExportFormat = 'COCO' | 'YOLO' | 'CVAT' | 'PASCAL_VOC';

/** 전송 대상 — 현재는 포털 서버만 지원 */
export type ExportTargetServer = 'PORTAL';

/**
 * 내보내기 요청 — 포털 서버로 학습데이터셋을 전송한다.
 * deidentify가 true면 비식별 처리(마스킹) 후 전송, false면 원본 전송.
 * forceReexport가 true면 이미 EXPORTED인 영상도 재전송한다(특수 상황 한정).
 */
export interface ExportRequest {
  format: ExportFormat;
  videoIds: string[];
  options: {
    includeLabels: boolean;
    includeImages: boolean;
  };
  deidentify: boolean;
  targetServer: ExportTargetServer;
  /** 이미 내보낸(EXPORTED) 영상도 강제 재전송 — 기본 false */
  forceReexport?: boolean;
}

/**
 * 내보내기 응답 — 포털 전송 작업 시작 결과.
 * 이번 전송 시도에서 비식별 실패로 인해 실패 처리된 영상 수, 이미 전송됨으로 스킵된 수가 함께 반환된다.
 */
export interface ExportResponse {
  jobId: string;
  count: number;
  message: string;
  deidentify: boolean;
  targetServer: ExportTargetServer;
  /**
   * 이번 전송 시도에서 비식별 실패로 인해 실패 처리된 영상 수.
   * 영상의 영구 속성이 아니라 시도 결과 — 다음 번에 다시 선택·전송 가능하다.
   */
  failedDueDeident?: number;
  /** 이미 EXPORTED라서 스킵된 영상 수 (forceReexport=true이면 0) */
  skippedAlreadyExported?: number;
  /**
   * 검수 미완료(taskStatus !== 'COMPLETED')로 차단된 영상 수.
   * 내보내기는 항상 검수 완료 영상만 가능하며 forceReexport와 무관하게 차단된다.
   */
  blockedNotApproved?: number;
}
