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
  async (err: AxiosError<ApiResponse<unknown>>) => {
    const status = err.response?.status ?? 0;

    if (status === 401) {
      // Race 방어: 토큰 적재 직전에 발사된 요청은 Authorization 헤더가 비어 있어 401 을 받는다.
      // 이 경우 (현재 store 에 토큰이 있고, 1차 시도에서 헤더 없이 보냈다면) 한 번만 재시도.
      const config = err.config as (InternalAxiosRequestConfig & {
        _retriedWithToken?: boolean;
      }) | undefined;
      const currentToken = useAuthStore.getState().token;
      const sentAuth = config?.headers?.get?.('Authorization') ?? config?.headers?.Authorization;
      if (
        config &&
        currentToken &&
        !sentAuth &&
        !config._retriedWithToken
      ) {
        config._retriedWithToken = true;
        config.headers?.set?.('Authorization', `Bearer ${currentToken}`);
        return apiClient.request(config);
      }
      // 정상 401 — 토큰 제거 + 상위 시스템 로그인 페이지로 이동
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
