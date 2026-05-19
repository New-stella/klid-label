// 시스템 설정 API — BE: /api/v1/manage/configs
//
// BE 시그니처 (kr.co.cudo.authoring.sysconfig.controller.SystemConfigController):
//   GET  /v1/manage/configs              → List<ConfigResponse>
//   PUT  /v1/manage/configs/{key}        body: { value: string }
//
// hotfix(W-1): 이전에는 PUT `/manage/configs` (path variable 누락) + body `{key, value}` 로 호출해
// 운영에서 404를 받는 정합 이슈가 있었다. BE 시그니처에 맞춰 path 에 key 를 싣고
// body 는 BE DTO(ConfigUpdateRequest) 와 동일하게 `{value}` 만 보낸다.

import { apiClient } from '@/lib/api/client';

import type { ConfigItem, ConfigUpdateRequest } from './types';

export function getConfigs() {
  return apiClient.get<ConfigItem[]>('/manage/configs').then((r) => r.data);
}

/**
 * 보안: 키/값은 zod 검증 후 호출 — 범위 외 값 차단.
 * BE는 서버 측에서 다시 한 번 검증한다 (이중 방어).
 * key 는 path 로 보내며 axios 가 안전하게 URL 인코딩한다 (Injection 방지).
 */
export function updateConfig(body: ConfigUpdateRequest) {
  // BE 는 value 만 받음. key 는 path variable.
  const path = `/manage/configs/${encodeURIComponent(body.key)}`;
  return apiClient.put<ConfigItem>(path, { value: String(body.value) }).then((r) => r.data);
}
