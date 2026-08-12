// 비식별 신고 관리 API (REVIEWER).
//
// 보안:
// - status/page/size 는 타입 강제 — axios 자동 URL 인코딩 (XSS/Injection 방어).
// - 목록은 REVIEWER 전용 (BE @PreAuthorize). resolve 는 본인 배정/REVIEWER (BE 책임).

import { apiClient } from '@/lib/api/client';
import type { PageResponse } from '@/lib/api/types';

import type {
  DeidentCandidate,
  DeidentReportRow,
  ListDeidentReportsParams,
} from './reportTypes';

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
 * 재비식별 산출물 후보 목록 조회.
 * BE: GET /api/v1/deident-reports/{rprtSn}/deident-candidates
 *
 * 산출물이 없으면 빈 배열 + 200 이다(에러 아님 — 아직 외부 비식별을 하지 않은 정상 상태).
 */
export function listDeidentCandidates(rprtSn: number): Promise<DeidentCandidate[]> {
  return apiClient
    .get<DeidentCandidate[]>(`/deident-reports/${rprtSn}/deident-candidates`)
    .then((r) => r.data);
}

/**
 * 비식별 신고 수동 해소 (외부 솔루션 비식별화 완료 후).
 * BE: POST /api/v1/deident-reports/{rprtSn}/resolve
 *
 * ★ `fileName`(어느 산출물로 해소하는가)은 **필수**다. 서버는 기본값을 고르지 않으며, 후보 목록과
 * 대조해 그 이름이 목록에 있을 때만 수락한다(목록이 곧 허용목록). 화면은 사용자가 고르기 전까지
 * 이 함수를 호출하지 않는다.
 */
export function resolveDeidentReport(
  rprtSn: number,
  fileName: string,
): Promise<void> {
  return apiClient
    .post(`/deident-reports/${rprtSn}/resolve`, { fileName })
    .then(() => undefined);
}
