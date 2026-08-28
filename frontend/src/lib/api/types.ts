// BE common.response.ApiResponse 미러
export interface ApiResponse<T> {
  success: boolean;
  data: T | null;
  message: string | null;
  errorCode: string | null;
}

// BE common.exception.ErrorCode 미러
export const ErrorCode = {
  INVALID_INPUT: 'INVALID_INPUT',
  UNAUTHORIZED: 'UNAUTHORIZED',
  FORBIDDEN: 'FORBIDDEN',
  NOT_FOUND: 'NOT_FOUND',
  CONFLICT: 'CONFLICT',
  PRECONDITION_FAILED: 'PRECONDITION_FAILED',
  PAYLOAD_TOO_LARGE: 'PAYLOAD_TOO_LARGE',
  TOO_MANY_REQUESTS: 'TOO_MANY_REQUESTS',
  EXTERNAL_API_ERROR: 'EXTERNAL_API_ERROR',
  INTERNAL_ERROR: 'INTERNAL_ERROR',
} as const;
export type ErrorCode = (typeof ErrorCode)[keyof typeof ErrorCode];

/**
 * 인가 역할 — 서버 역할 enum 의 미러.
 *
 * 선언 순서는 권한이 넓은 쪽부터다(관리자 · 검수자 · 작업자 · 포털 회원). 순서 자체가 인가를
 * 결정하지는 않는다 — 역할 사이의 포함 관계 판정은 `@/lib/authz` 한 곳이 소유한다.
 *
 * ⚠ 이 객체는 **값의 집합**만 선언한다. 어떤 역할이 어떤 역할을 대신할 수 있는지는 여기에
 * 적지 않는다(적으면 판정이 두 곳으로 갈린다).
 */
export const Role = {
  ADMIN: 'ADMIN',
  REVIEWER: 'REVIEWER',
  WORKER: 'WORKER',
  PORTAL_USER: 'PORTAL_USER',
} as const;
export type Role = (typeof Role)[keyof typeof Role];

export const Channel = {
  INTERNAL: 'INTERNAL',
  PORTAL: 'PORTAL',
} as const;
export type Channel = (typeof Channel)[keyof typeof Channel];

export interface TokenClaims {
  sub: string;
  /**
   * 부여된 역할.
   *
   * Phase 2 (권한 자가 부여): BE 는 인증은 되었으나 권한이 부여되지 않은 사용자에게
   * `role` 클레임이 비어 있는 토큰을 발급할 수 있다. 이 경우 FE 는 `/role-claim` 경로로
   * redirect 하여 사용자가 직접 역할을 부여받도록 안내한다.
   */
  role: Role | null;
  channel: Channel;
  exp: number;
  name?: string;
}

export interface PageResponse<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}
