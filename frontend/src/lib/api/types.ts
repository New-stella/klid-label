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
  PAYLOAD_TOO_LARGE: 'PAYLOAD_TOO_LARGE',
  EXTERNAL_API_ERROR: 'EXTERNAL_API_ERROR',
  INTERNAL_ERROR: 'INTERNAL_ERROR',
} as const;
export type ErrorCode = (typeof ErrorCode)[keyof typeof ErrorCode];

export const Role = {
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
