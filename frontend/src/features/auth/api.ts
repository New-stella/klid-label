import { apiClient } from '@/lib/api/client';
import type { Channel, Role } from '@/lib/api/types';

export interface MeResponse {
  sub: string;
  /** 서버 인가 role. 무권한(관제 진입 직후 role=null)일 수 있다 — 계약상 nullable. */
  role: Role | null;
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

/**
 * 관리자 부트스트랩 창구의 개폐 상태. [@design API-245]
 *
 * ★담기는 사실은 <b>열림/닫힘 하나</b>다 — 관리자 인원수는 오지 않는다(계약).
 */
export interface RoleClaimAvailability {
  /** `true`=열림(시스템에 관리자가 한 명도 없다) / `false`=닫힘(관리자가 한 명 이상 있다). */
  available: boolean;
}

/**
 * 관리자 부트스트랩 창구가 <b>지금</b> 열려 있는지 묻는다. [@design API-245] [@design SCREEN-002]
 *
 * 이 조회가 없으면 화면은 개폐를 <b>제출 응답으로 사후에</b> 알게 된다 — 관리자가 이미 있는
 * 시스템에서도 *"아직 관리자가 없습니다"* 를 먼저 띄우고, 사용자가 패스워드를 넣어 제출한 뒤에야
 * 거절을 받는 막다른 길이 된다. 그것이 이 창구를 두는 이유다.
 *
 * ★<b>이 값은 순간의 상태다.</b> 조회와 제출 사이에 다른 사람이 최초 관리자가 되면 창은 그
 * 사이에 닫히고 제출은 409 로 거절된다. 그래서 이 조회는 첫 화면을 사실에 맞추는 수단일 뿐이며,
 * 호출하는 화면은 <b>제출이 창 닫힘으로 거절되는 갈래를 그대로 유지해야 한다</b>
 * ({@link claimRole} 의 409 처리를 이 조회로 대신할 수 없다).
 *
 * ⚠ `skipAuthRedirect` 를 켜지 않는다 — 여기서의 401 은 «그 요청 고유의 자격 검증 실패»가 아니라
 * <b>세션이 유효하지 않다</b>는 뜻이라, 상위 시스템 로그인으로 되돌리는 기본 동작이 정확하다.
 * (자격증명을 받는 {@link claimRole} 과 다른 축이다.)
 */
export async function getRoleClaimAvailability(): Promise<RoleClaimAvailability> {
  const res = await apiClient.get<RoleClaimAvailability>('/auth/role-claim/availability');
  return res.data;
}
