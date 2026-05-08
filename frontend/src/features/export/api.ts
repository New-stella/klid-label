// 내보내기 도메인 API — BE: /api/v1/exports/*.
//
// 보안:
// - axios가 path/body 파라미터 자동 URL 인코딩 (XSS/Injection 방어).
// - format은 ExportFormat enum 타입으로 강제 (allowlist).
// - REVIEWER 역할 검증은 라우터 RoleGuard + BE @PreAuthorize.

import { apiClient } from '@/lib/api/client';

import type {
  DatasetOption,
  ExportStatusInfo,
  PrepareExportRequest,
  PrepareExportResponse,
} from './types';

/**
 * 내보내기 준비 요청 (미리보기 + 실행 진입점).
 * BE: POST /api/v1/exports/prepare — record(pjtId, format)
 *
 * FE 의 datasetId 는 BE alias 인 pjtId 로 매핑하여 송신한다.
 */
export function prepareExport(
  body: PrepareExportRequest,
): Promise<PrepareExportResponse> {
  // BE 기대 필드 (pjtId, format) + FE 호환 필드(datasetId/videoIds/nasPath) 동시 송신
  const payload = {
    pjtId: body.datasetId,
    datasetId: body.datasetId,
    format: body.format,
    videoIds: body.videoIds ?? [],
    nasPath: body.nasPath,
  };
  return apiClient
    .post<PrepareExportResponse>('/exports/prepare', payload)
    .then((r) => r.data);
}

/**
 * 내보내기 상태 조회.
 * BE: GET /api/v1/exports/{id}/status
 */
export function getExportStatus(id: number): Promise<ExportStatusInfo> {
  return apiClient
    .get<ExportStatusInfo>(`/exports/${id}/status`)
    .then((r) => r.data);
}

/**
 * 데이터셋 목록 (라디오 옵션).
 * BE: GET /api/v1/exports/datasets — Page<ExportStatusResponse> 또는 DatasetOption[] 반환
 */
export function listDatasets(): Promise<DatasetOption[]> {
  return apiClient
    .get<DatasetOption[] | { content: DatasetOption[] }>('/exports/datasets')
    .then((r) => {
      const data = r.data as DatasetOption[] | { content: DatasetOption[] };
      if (Array.isArray(data)) return data;
      if (data && Array.isArray((data as { content?: unknown }).content)) {
        return (data as { content: DatasetOption[] }).content;
      }
      return [];
    });
}

/**
 * 최근 내보내기 이력 (mock 정합 — sticky 사이드 카드).
 * BE: GET /api/v1/exports/datasets?page=0&size=10 결과를 그대로 활용.
 */
export function listRecentExports(size = 5): Promise<ExportStatusInfo[]> {
  return apiClient
    .get<{ content: ExportStatusInfo[] } | ExportStatusInfo[]>(
      `/exports/datasets?page=0&size=${size}`,
    )
    .then((r) => {
      const data = r.data;
      if (Array.isArray(data)) return data;
      if (data && Array.isArray((data as { content?: unknown }).content)) {
        return (data as { content: ExportStatusInfo[] }).content;
      }
      return [];
    });
}
