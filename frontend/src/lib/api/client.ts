import axios, { AxiosError, AxiosResponse, InternalAxiosRequestConfig } from 'axios';


import { detectChannel, redirectToUpstream } from '@/features/auth/redirectToUpstream';
import { useAuthStore } from '@/stores/useAuthStore';

import { ApiError } from './errors';
import type { ApiResponse } from './types';


// ApiResponse.message 를 unwrap 후에도 보존하기 위한 타입 확장 — 인터셉터가 data 를 꺼내면서
// 함께 message 를 응답 객체에 실어준다(예: SAM2/오토라벨 mock 안내). 런타임 영향 없는 optional 필드.
// 타입 파라미터는 axios 원본 선언(any)과 동일해야 병합된다(TS2428).
declare module 'axios' {
  /* eslint-disable @typescript-eslint/no-explicit-any, @typescript-eslint/no-unused-vars */
  export interface AxiosResponse<T = any, D = any> {
    /** BE ApiResponse.message — 인터셉터가 data unwrap 시 함께 보존. */
    message?: string | null;
  }

  export interface AxiosRequestConfig<D = any> {
    /**
     * 401 을 **로그인 세션 만료로 해석하지 않는다**(R11).
     *
     * 기본 동작은 401 = 인계 토큰 만료 → 토큰 삭제 + 상위 시스템 로그인 페이지로 이동이다.
     * 그런데 401 이 **그 요청 고유의 자격 검증 실패**를 뜻하는 엔드포인트가 있다(관리자 공유
     * 패스워드 확인). 거기서 기본 동작이 돌면 패스워드 오타 한 번에 저작도구에서 통째로
     * 로그아웃된다. 그런 요청만 이 플래그를 켠다.
     */
    skipAuthRedirect?: boolean;
  }
  /* eslint-enable @typescript-eslint/no-explicit-any, @typescript-eslint/no-unused-vars */
}

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
      return { ...res, data: body.data, message: body.message };
    }
    return res;
  },
  async (err: AxiosError<ApiResponse<unknown>>) => {
    const status = err.response?.status ?? 0;

    if (status === 401 && !err.config?.skipAuthRedirect) {
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
