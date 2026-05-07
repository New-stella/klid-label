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
  role: Role;
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
