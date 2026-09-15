// 시스템 설정 API — BE: /api/v1/manage/configs
//
// BE 시그니처 (kr.co.cudo.authoring.sysconfig.controller.SystemConfigController):
//   GET  /v1/manage/configs              → List<ConfigResponse>
//   PUT  /v1/manage/configs/{key}        body: { value: string }
//
// hotfix(W-1): 이전에는 PUT `/manage/configs` (path variable 누락) + body `{key, value}` 로 호출해
// 운영에서 404를 받는 정합 이슈가 있었다. BE 시그니처에 맞춰 path 에 key 를 싣고
// body 는 BE DTO(ConfigUpdateRequest) 와 동일하게 `{value}` 만 보낸다.

import { ADMIN_SESSION_HEADER, adminSessionHeaders } from '@/features/adminSession/api';
import { aiDefaultsPath } from '@/lib/api/aiRoutes';
import { apiClient } from '@/lib/api/client';

import type { AiDefaults, ConfigItem, ConfigUpdateRequest } from './types';

// 헤더 이름의 단일 지점은 관리자 유효창 모듈이다 — 유효창이 관리 기능 공통으로 넓어지면서
// 이 파일이 더는 그 계약의 소유자가 아니다. 기존 import 경로 호환을 위해 재노출만 한다.
export { ADMIN_SESSION_HEADER };

export function getConfigs() {
  return apiClient.get<ConfigItem[]>('/manage/configs').then((r) => r.data);
}

/**
 * AI 정밀도 기본값 조회 — `GET /v1/ai-defaults` (검수자·작업자 공통).
 *
 * 관리 영역(`/manage/**`) 밖의 별도 경로다. `getConfigs()` 는 검수자 전용이라 작업자가 부르면
 * 403 이 쌓이고, 응답에 설정 전량과 마지막 수정자 계정 식별자가 함께 실린다.
 *
 * @param portal 포털 채널이면 `GET /v1/portal/ai-defaults`(포털 회원 · 응답 동일). 내부 창구는
 *   포털 토큰이 닿지 않아 403 이다. 판정은 호출부(라벨링 화면의 portalMode)가 한다.
 */
export function getAiDefaults(portal = false) {
  return apiClient.get<AiDefaults>(aiDefaultsPath(portal)).then((r) => r.data);
}

/**
 * 보안: 키/값은 zod 검증 후 호출 — 범위 외 값 차단.
 * BE는 서버 측에서 다시 한 번 검증한다 (이중 방어).
 * key 는 path 로 보내며 axios 가 안전하게 URL 인코딩한다 (Injection 방지).
 */
export function updateConfig(body: ConfigUpdateRequest) {
  // BE 는 value 만 받음. key 는 path variable.
  const path = `/manage/configs/${encodeURIComponent(body.key)}`;
  // R11 — 관리자 세션 토큰은 **헤더**로 보낸다. 바디에 두면 설정 값과 자격증명이 한 구조에 섞여
  // 로그·검증 경로마다 자격증명이 딸려 다닌다. 없으면 헤더 자체를 붙이지 않는다.
  const headers = adminSessionHeaders(body.adminSessionToken);
  return apiClient
    .put<ConfigItem>(path, { value: String(body.value) }, { headers })
    .then((r) => r.data);
}

// 유효창 개시(`POST /v1/manage/admin-session`)는 관리자 유효창 모듈이 소유한다 —
// 이제 연동 주소 전용이 아니라 관리 기능 공통 진입이라 이 파일의 소관이 아니다.
export { openAdminSession } from '@/features/adminSession/api';
