// 내보내기 도메인 타입 (BE OpenAPI alias) — UI/UX §4-12 정합.
//
// V1.4: 데이터마트 검색/다운로드 UI 미제공. 학습데이터셋 NAS 내보내기까지만 담당.

export const ExportFormat = {
  COCO: 'COCO',
  YOLO: 'YOLO',
  /** 영상 + Chain-of-Thought (생성형 AI 학습용) */
  VIDEO_COT: 'VIDEO_COT',
} as const;
export type ExportFormat = (typeof ExportFormat)[keyof typeof ExportFormat];

export const ExportStatus = {
  PREPARING: 'PREPARING',
  READY: 'READY',
  FAILED: 'FAILED',
} as const;
export type ExportStatus = (typeof ExportStatus)[keyof typeof ExportStatus];

export interface ExportPreview {
  videoCount: number;
  frameCount: number;
  labelCount: number;
}

export interface PrepareExportRequest {
  /** 데이터셋 식별자 (BE에서 dataset/SQL allowlist 검증) */
  datasetId: number;
  format: ExportFormat;
  /** NAS 경로. 사용자 입력 → BE에서 path traversal 검증 */
  nasPath: string;
}

export interface PrepareExportResponse {
  exportId: number;
  status: ExportStatus;
  preview: ExportPreview;
}

export interface ExportStatusInfo {
  exportId: number;
  status: ExportStatus;
  preview?: ExportPreview;
  /** 실패 시 사유 */
  errorMessage?: string;
  completedAt?: string;
}

export interface DatasetOption {
  id: number;
  name: string;
  videoCount: number;
}
