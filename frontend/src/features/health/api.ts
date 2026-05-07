// 헬스 체크 API — BE: /api/v1/manage/health (외부 의존성 포함)

import { apiClient } from '@/lib/api/client';

import type { HealthResponse } from './types';

/** 5초 폴링 (HealthStatusList refetchInterval) */
export const HEALTH_POLL_INTERVAL_MS = 5000;

export function getHealth() {
  return apiClient.get<HealthResponse>('/manage/health').then((r) => r.data);
}
