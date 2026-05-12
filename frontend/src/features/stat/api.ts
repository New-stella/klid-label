// 통계 도메인 API — BE: /api/v1/stats/*
//
// 보안:
// - period 파라미터는 정규식 검증 (allowlist) — Path/Command Injection 차단
// - 리포트 다운로드 응답은 blob 처리, 파일명은 BE Content-Disposition 신뢰 (사용자 입력 미반영)

import { apiClient } from '@/lib/api/client';

import type { OverallStatSummary, StatPeriod, WorkerStatSummary } from './types';

const PERIOD_REGEX = /^(WEEK|MONTH|QUARTER|YEAR)$/;

function assertPeriod(period: StatPeriod): StatPeriod {
  if (!PERIOD_REGEX.test(period)) {
    throw new Error(`잘못된 period 값: ${period}`);
  }
  return period;
}

/** 작업자 본인 통계 — REVIEWER는 workerId로 임의 작업자 조회 가능. */
export function getWorkerDashboard(
  workerId?: number | string,
): Promise<WorkerStatSummary> {
  return apiClient
    .get<WorkerStatSummary>('/stats/worker', {
      params: workerId !== undefined && workerId !== null ? { workerId } : undefined,
    })
    .then((r) => r.data);
}

/** REVIEWER 전체 구축 현황. */
export function getOverallStats() {
  return apiClient.get<OverallStatSummary>('/stats/overall').then((r) => r.data);
}

/** REVIEWER 리포트 다운로드 (CSV blob). */
export function downloadReport(period: StatPeriod): Promise<Blob> {
  return Promise.resolve()
    .then(() => assertPeriod(period))
    .then((safe) =>
      apiClient
        .get('/stats/report', {
          params: { period: safe },
          responseType: 'blob',
          // ApiResponse 래핑 우회 — blob 응답
          transformResponse: (raw) => raw,
        })
        .then((r) => {
          const data = r.data;
          // jsdom + axios-mock-adapter는 blob 미지원 → string으로 떨어짐. 안전하게 Blob 래핑.
          if (data instanceof Blob) return data;
          return new Blob([typeof data === 'string' ? data : JSON.stringify(data)], {
            type: 'text/csv',
          });
        }),
    );
}
