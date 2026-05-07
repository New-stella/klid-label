// 시스템 설정 API — BE: /api/v1/manage/configs

import { apiClient } from '@/lib/api/client';

import type { ConfigItem, ConfigUpdateRequest } from './types';

export function getConfigs() {
  return apiClient.get<ConfigItem[]>('/manage/configs').then((r) => r.data);
}

/**
 * 보안: 키/값은 zod 검증 후 호출 — 범위 외 값 차단.
 * BE는 서버 측에서 다시 한 번 검증한다 (이중 방어).
 */
export function updateConfig(body: ConfigUpdateRequest) {
  return apiClient.put<ConfigItem>('/manage/configs', body).then((r) => r.data);
}
