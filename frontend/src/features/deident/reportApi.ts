// 비식별 신고 관리 API (REVIEWER).
//
// 보안:
// - status/page/size 는 타입 강제 — axios 자동 URL 인코딩 (XSS/Injection 방어).
// - 목록은 REVIEWER 전용 (BE @PreAuthorize). resolve 는 본인 배정/REVIEWER (BE 책임).

import { apiClient } from '@/lib/api/client';
import type { PageResponse } from '@/lib/api/types';

import type { DeidentReportRow, ListDeidentReportsParams } from './reportTypes';

/**
 * 비식별 신고 목록 조회 (REVIEWER).
 * BE: GET /api/v1/deident-reports?status=OPEN|RESOLVED&page=&size=
 */
export function listDeidentReports(
  params: ListDeidentReportsParams = {},
): Promise<PageResponse<DeidentReportRow>> {
  return apiClient
    .get<PageResponse<DeidentReportRow>>('/deident-reports', { params })
    .then((r) => r.data);
}

/**
 * 비식별 신고 수동 해소 (외부 솔루션 비식별화 완료 후).
 * BE: POST /api/v1/deident-reports/{rprtSn}/resolve
 */
export function resolveDeidentReport(rprtSn: number): Promise<void> {
  return apiClient
    .post(`/deident-reports/${rprtSn}/resolve`)
    .then(() => undefined);
}
