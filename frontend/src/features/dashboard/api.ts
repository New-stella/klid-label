// 대시보드 도메인 API — BE: /api/v1/stats/summary (Phase 12에서 본격 연결)

import { apiClient } from '@/lib/api/client';

import type { DashboardSummary } from './types';

export function getDashboardSummary() {
  return apiClient.get<DashboardSummary>('/stats/summary').then((r) => r.data);
}
