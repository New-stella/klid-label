// 관리자 단기 유효창 API — BE: /api/v1/manage/admin-session · /api/v1/manage/admin-password
//
// [@design API-194] [@design API-223] [@design ADR-046]

import { apiClient } from '@/lib/api/client';

import type { AdminSessionIssued } from './store';

/**
 * 관리자 유효창 토큰을 싣는 헤더. BE `AdminSessionGate.HEADER`.
 *
 * ★**조회에는 절대 붙이지 않는다.** 특히 `GET /v1/users/workers` 는 작업 배정 화면이 읽으므로,
 * 「일관성」을 이유로 조회에까지 요건을 얹으면 배정 흐름이 통째로 끊긴다.
 */
export const ADMIN_SESSION_HEADER = 'X-Admin-Session';

/**
 * 토큰이 있을 때만 헤더 객체를 만든다 — 없으면 `undefined` 라 헤더 자체가 붙지 않는다.
 *
 * 빈 문자열 헤더를 보내면 서버 로그에 「헤더는 왔는데 값이 없다」가 쌓여 원인 추적이 흐려진다.
 */
export function adminSessionHeaders(token?: string): Record<string, string> | undefined {
  return token ? { [ADMIN_SESSION_HEADER]: token } : undefined;
}

/**
 * 관리자 단기 유효창을 연다 (`POST /v1/manage/admin-session`).
 *
 * ⚠ `skipAuthRedirect` 를 켠다. 여기서의 401 은 **관리자 패스워드 불일치**이지 로그인 세션 만료가
 * 아니다. 전역 인터셉터의 기본 동작(토큰 삭제 + 상위 시스템 로그인 페이지로 이동)이 그대로 돌면,
 * 패스워드를 한 번 잘못 친 검수자가 **저작도구에서 통째로 로그아웃된다**.
 */
export function openAdminSession(adminPassword: string) {
  return apiClient
    .post<AdminSessionIssued>(
      '/manage/admin-session',
      { adminPassword },
      { skipAuthRedirect: true },
    )
    .then((r) => r.data);
}

export interface AdminPasswordChangeRequest {
  currentPassword: string;
  newPassword: string;
}

/**
 * 관리자 공유 패스워드를 교체한다 (`PUT /v1/manage/admin-password`).
 *
 * 요구는 셋이 함께 성립해야 한다 — 검수자 권한, 유효한 관리자 유효창, 현재 패스워드 재확인.
 *
 * ★**성공하면 그 전에 발급된 유효창이 전부 무효가 된다 — 방금 이 요청에 쓴 것도 포함이다.**
 * 서명키가 현재 패스워드 해시에서 유도되기 때문이며 무효화 장부가 따로 없다. 호출부는 성공 직후
 * 반드시 스토어의 토큰을 버려야 한다. 버리지 않으면 이미 죽은 토큰을 계속 실어 보내 사용자가
 * 영문 모를 403 을 반복해 받는다.
 *
 * ⚠ `skipAuthRedirect` 를 켠다 — 현재 패스워드 불일치가 401 이라 전역 인터셉터가 그대로 돌면
 * 오타 한 번에 저작도구에서 로그아웃된다(유효창 개시와 같은 이유).
 *
 * 보안: 두 평문은 이 함수 호출 동안만 존재해야 한다. 반환값에도 로그에도 남기지 않는다.
 */
export function changeAdminPassword(body: AdminPasswordChangeRequest, token?: string) {
  return apiClient
    .put<null>('/manage/admin-password', body, {
      headers: adminSessionHeaders(token),
      skipAuthRedirect: true,
    })
    .then(() => undefined);
}
