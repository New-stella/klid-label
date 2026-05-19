// 포털 채널 도메인 타입 (V1.5)
// SCR-PORTAL-001/002 — 외부 사용자 본인 업로드 데이터만 처리.
// 다운로드 UI는 포털 자체 책임 — 저작도구 mock에서 미제공.

export type PortalUploadStatus =
  | 'UPLOADING'
  | 'COMPLETED'
  | 'AUTOLABEL_PENDING'
  | 'AUTOLABEL_DONE'
  | 'FAILED';

export interface PortalUpload {
  /** 백엔드 PK — 라벨링 페이지 진입 시 srcSn으로 사용 */
  srcSn: number;
  /** 표시용 파일명 (BE 측에서 sanitize된 결과) */
  displayName: string;
  /** 업로드 시각 ISO8601 */
  uploadedAt: string;
  /** 처리 상태 */
  status: PortalUploadStatus;
  /** 용량(byte) */
  fileSize: number;
}

/**
 * 오토라벨링 응답.
 * BE: `kr.co.cudo.authoring.common.client.dto.YoloResponse` 매핑.
 *  - detections[].label   : 클래스 라벨
 *  - detections[].points  : [x1, y1, x2, y2]
 *  - detections[].score   : 신뢰도 0.0~1.0
 *  - detections[].trackId : track 경로 외에는 null
 *  - mock                 : ai-server 가 mock 응답을 반환했는지 여부
 *  - source               : "mock" | "model"
 *  - mockReason           : mock 인 경우 사유 (env_mock|weights_missing|load_failed)
 */
export interface PortalAutolabelDetection {
  label: string;
  points: number[];
  score: number;
  trackId?: number | null;
}

export interface PortalAutolabelResponse {
  detections: PortalAutolabelDetection[];
  mock: boolean;
  source: 'mock' | 'model' | string;
  mockReason?: string | null;
}

/** 업로드 사전 검증 실패 사유 */
export type UploadValidationError =
  | 'EXTENSION_NOT_ALLOWED'
  | 'FILE_TOO_LARGE'
  | 'MIME_NOT_ALLOWED'
  | 'EMPTY_FILE';

export interface UploadValidationResult {
  valid: boolean;
  error?: UploadValidationError;
}

/** TUS 업로드 진행 상태 */
export type TusUploadStatus = 'IDLE' | 'UPLOADING' | 'PAUSED' | 'COMPLETED' | 'FAILED';

export interface TusUploadProgress {
  bytesUploaded: number;
  bytesTotal: number;
  percent: number;
}
