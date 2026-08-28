import { apiClient } from '@/lib/api/client';
import type { Channel, Role } from '@/lib/api/types';

export interface MeResponse {
  sub: string;
  role: Role;
  channel: Channel;
  name?: string;
}

/**
 * 보안: BE가 검증한 JWT 클레임을 다시 받아 FE 메모리 클레임과 동기화 검증한다.
 */
export async function getMe(): Promise<MeResponse> {
  const res = await apiClient.get<MeResponse>('/me');
  return res.data;
}

/**
 * 관리자 부트스트랩 요청 — 인증되었으나 role 이 부여되지 않은 사용자가 관리자 패스워드와 함께
 * 본인에게 **관리자 역할**을 부여한다. 성공 시 새 토큰을 즉시 반환받아 useAuthStore 를 교체한다.
 *
 * ⚠ **구 서술 폐기(2026-08-28 · ADR-055)** — *"본인에게 WORKER/REVIEWER 역할을 부여한다"*.
 * 부여 역할은 **관리자 고정**이고 이 필드는 결과를 바꾸지 못한다. 이 창구는 **관리자가 0명일
 * 때만** 열리며, 한 명이라도 생기면 닫힌다(그 뒤로는 관리자가 사용자 관리 화면에서 역할을 준다).
 * 되살리면 사용자가 고른 값과 실제 부여 역할이 갈려 화면이 거짓을 말하게 된다.
 *
 * 보안:
 * - apiClient (axios) 가 본문을 JSON 으로 직렬화/URL 자동 인코딩한다.
 * - 평문 패스워드는 본 함수 호출 인자 범위에서만 메모리에 존재하며 절대 로그/세션스토리지에
 *   기록하지 않는다 (CWE-256/532).
 * - 응답에는 새 accessToken 만 포함되며 BCrypt 해시는 절대 포함되지 않는다.
 */
export interface ClaimRoleRequest {
  /**
   * 요청 역할 — **결과를 바꾸지 못한다**(부여 역할은 관리자 고정). 서버 DTO 가 필수라 생략할 수
   * 없어 부여될 역할과 같은 값을 명시적으로 싣는다. `PORTAL_USER` 만 400 으로 거절된다.
   */
  role: 'ADMIN';
  adminPassword: string;
  /**
   * 관제서버가 localStorage 로 인계한 표시용 사용자 정보 (선택).
   * BE 가 사용자 마스터에 자동등록·갱신하는 값이며, 사용자 식별(userNo)은 <JWT subject> 로만
   * 이루어진다 — 이 값들은 표시 이름일 뿐 권한·식별에 쓰이지 않는다.
   */
  userId?: string;
  userNm?: string;
}

export interface ClaimRoleResponse {
  accessToken: string;
  role: Role;
  userNo: number;
  userName: string;
}

export async function claimRole(body: ClaimRoleRequest): Promise<ClaimRoleResponse> {
  const res = await apiClient.post<ClaimRoleResponse>('/auth/role-claim', body);
  return res.data;
}
