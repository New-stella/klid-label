import axios, { AxiosError, AxiosResponse, InternalAxiosRequestConfig } from 'axios';


import { detectChannel, redirectToUpstream } from '@/features/auth/redirectToUpstream';
import { useAuthStore } from '@/stores/useAuthStore';

import { ApiError } from './errors';
import type { ApiResponse } from './types';


// 보안: VITE_API_BASE_URL은 환경변수에서만 로드 (사용자 입력 금지)
const baseURL = (import.meta.env.VITE_API_BASE_URL as string | undefined) ?? '/api/v1';

export const apiClient = axios.create({
  baseURL,
  withCredentials: true,
  timeout: 30000,
});

apiClient.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  const token = useAuthStore.getState().token;
  if (token) {
    config.headers.set('Authorization', `Bearer ${token}`);
  }
  return config;
});

apiClient.interceptors.response.use(
  (res: AxiosResponse) => {
    const body = res.data as ApiResponse<unknown> | unknown;
    if (isApiResponse(body)) {
      if (body.success === false) {
        throw ApiError.fromBody(body, res.status);
      }
      return { ...res, data: body.data };
    }
    return res;
  },
  (err: AxiosError<ApiResponse<unknown>>) => {
    const status = err.response?.status ?? 0;

    if (status === 401) {
      // 보안: 만료/인증 실패 시 메모리 토큰 즉시 제거
      useAuthStore.getState().clear();
      redirectToUpstreamLogin();
    }

    const body = err.response?.data;
    if (isApiResponse(body)) {
      return Promise.reject(ApiError.fromBody(body, status));
    }
    return Promise.reject(ApiError.fromStatus(status, err.message));
  },
);

function isApiResponse(value: unknown): value is ApiResponse<unknown> {
  if (typeof value !== 'object' || value === null) return false;
  const v = value as Record<string, unknown>;
  return typeof v.success === 'boolean' && 'data' in v && 'errorCode' in v;
}

/**
 * 보안: redirect URL은 환경변수에서만 사용 — Open Redirect 방어.
 * channel 클레임 우선, 없으면 URL 경로로 추론.
 *
 * @deprecated Phase 1+: `@/features/auth/redirectToUpstream`을 직접 사용하세요.
 *   이 함수는 기존 client.test 호환을 위해 유지됩니다.
 */
export function redirectToUpstreamLogin(): void {
  const claims = useAuthStore.getState().claims;
  redirectToUpstream(claims?.channel ?? detectChannel());
}
