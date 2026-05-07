// 내보내기 도메인 API — BE: /api/v1/exports/*.
//
// 보안:
// - axios가 path/body 파라미터 자동 URL 인코딩 (XSS/Injection 방어).
// - NAS 경로(nasPath)는 사용자 입력 — Path Traversal(`..` 포함) 차단은 BE 책임.
//   FE는 기본 가드(공백/길이) + zod 1차 검증만 수행.
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
 * BE: POST /api/v1/exports/prepare
 */
export function prepareExport(
  body: PrepareExportRequest,
): Promise<PrepareExportResponse> {
  return apiClient
    .post<PrepareExportResponse>('/exports/prepare', body)
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
 * BE: GET /api/v1/exports/datasets
 */
export function listDatasets(): Promise<DatasetOption[]> {
  return apiClient.get<DatasetOption[]>('/exports/datasets').then((r) => r.data);
}
