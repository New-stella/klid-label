// 내보내기 도메인 타입 (BE OpenAPI alias) — UI/UX §4-12 정합.
//
// V1.4: 데이터마트 검색/다운로드 UI 미제공. 학습데이터셋 NAS 내보내기까지만 담당.

export const ExportFormat = {
  COCO: 'COCO',
  YOLO: 'YOLO',
  /** CVAT 호환 XML — 폴리곤·트랙 포함 */
  CVAT: 'CVAT',
  /** Pascal VOC XML — 이미지별 개별 어노테이션 */
  PASCAL_VOC: 'PASCAL_VOC',
} as const;
export type ExportFormat = (typeof ExportFormat)[keyof typeof ExportFormat];

/** BE 가 현재 지원하는 포맷 (Phase 10 기준). 그 외는 화면에서 비활성 표시. */
export const SUPPORTED_EXPORT_FORMATS: ExportFormat[] = [
  ExportFormat.COCO,
  ExportFormat.YOLO,
];

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
  /** 데이터셋(프로젝트) 식별자 — BE alias: pjtId */
  datasetId: number;
  format: ExportFormat;
  /** 선택된 영상 ID 목록 (mock 정합). 현재 BE 는 사용하지 않음(향후 확장 대비). */
  videoIds?: number[];
  /** NAS 경로. 기본값은 BE 가 결정. */
  nasPath?: string;
}

export interface PrepareExportResponse {
  exportId: number;
  status: ExportStatus;
  preview?: ExportPreview;
}

export interface ExportStatusInfo {
  exportId: number;
  status: ExportStatus;
  preview?: ExportPreview;
  /** 실패 시 사유 */
  errorMessage?: string;
  completedAt?: string;
  /** mock 정합 — 등록 시점 */
  registeredAt?: string;
  /** mock 정합 — NAS 경로(있으면) */
  nasPath?: string;
  format?: ExportFormat | string;
}

export interface DatasetOption {
  id: number;
  name: string;
  videoCount: number;
}
