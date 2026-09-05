import axios, { AxiosError, AxiosResponse, InternalAxiosRequestConfig } from 'axios';


import { detectChannel, redirectToUpstream } from '@/features/auth/redirectToUpstream';
import {
  buildAuthHeader,
  getAccessToken,
  resolveAuthHeaderName,
} from '@/features/auth/tokenHandoff';
import { resolveConfig } from '@/lib/runtimeConfig';
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

// 보안: API base 는 설정에서만 로드 (사용자 입력 금지).
//   해석 순서는 런타임(`klid-config.js`) → 빌드 → 기본값이다. 런타임 파일은 번들보다 먼저
//   로드되므로 이 모듈이 평가될 때 이미 값이 심겨 있다.
const baseURL = resolveConfig('VITE_API_BASE_URL') ?? '/api/v1';

export const apiClient = axios.create({
  baseURL,
  withCredentials: true,
  timeout: 30000,
});

// [@design INT-013]
// 토큰은 **인계 창구(`features/auth/tokenHandoff`)에 묻는다** — 스토어를 직접 읽지 않는다.
//
// 내부(관제) 채널에서는 창구가 그대로 스토어를 읽으므로 **동작이 바뀌지 않는다.** 포털 채널에서는
// 창구가 Host 에 매번 다시 물어, Host 가 세션을 갱신한 뒤에도 죽은 토큰을 붙잡지 않는다.
// 여기서 다시 스토어를 읽으면 포털 채널에서 그 파손이 되살아난다 — 근거 전문은 창구 파일 상단.
//
// 헤더 스킴도 **채널이 가른다** — 관제는 `Authorization: Bearer`, 포털은 전용 헤더
// `x-access-token`(Bearer 접두 없음). 판정·조립은 창구 파일의 `buildAuthHeader` 한 곳이 소유하며
// 여기서 채널을 다시 판정하지 않는다. 근거·함정(접두를 붙이면 401 · 두 헤더 동시 전송 시 401)은
// 그 함수의 주석 참조.
apiClient.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  const token = getAccessToken();
  if (token) {
    const { name, value } = buildAuthHeader(token);
    config.headers.set(name, value);
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
      // Race 방어: 토큰 적재 직전에 발사된 요청은 인증 헤더가 비어 있어 401 을 받는다.
      // 이 경우 (지금 창구에 토큰이 있고, 1차 시도에서 헤더 없이 보냈다면) 한 번만 재시도.
      //
      // ⚠ 되읽을 헤더 이름도 **채널이 가른다.** 여기에 `'Authorization'` 을 다시 적으면 포털
      //   채널에서는 언제나 「헤더가 없다」로 읽혀 붙어 나간 요청까지 한 번 더 쏘게 되고,
      //   그 재시도는 반대 채널 헤더를 덧붙여 **두 헤더 충돌 401** 을 만든다.
      const config = err.config as (InternalAxiosRequestConfig & {
        _retriedWithToken?: boolean;
      }) | undefined;
      const currentToken = getAccessToken();
      const authHeaderName = resolveAuthHeaderName();
      const sentAuth =
        config?.headers?.get?.(authHeaderName) ??
        (config?.headers as Record<string, unknown> | undefined)?.[authHeaderName];
      if (
        config &&
        currentToken &&
        !sentAuth &&
        !config._retriedWithToken
      ) {
        config._retriedWithToken = true;
        const { name, value } = buildAuthHeader(currentToken);
        config.headers?.set?.(name, value);
        return apiClient.request(config);
      }
      // 정상 401 — 토큰 제거 + 상위 시스템 로그인 페이지로 이동
      //
      // [@design INT-013] ★이 이동은 **포털 채널에서도 그대로 둔다.** 설계가 명시한다 —
      //   「토큰 만료로 상위 시스템 로그인 화면에 보내는 것은 그대로 둔다. 그건 Host 도 해야 할
      //   일이고, 저작도구는 복귀 주소에 현재 위치를 실어 보내므로 임베드된 상태에서 오히려
      //   정확하다. **이것까지 막으면 만료된 세션에 갇힌다**」.
      //
      //   같은 설계의 「Host 화면을 벗어나지 않는다」는 **다른 층**이다. 그쪽이 말하는 것은
      //   라우트 가드의 인증·권한 분기(`router/guards.tsx`)이고 이미 제자리 안내로 처리돼
      //   있다(`PortalEmbedNotice`). 여기는 **토큰 만료** 축이라 그 규칙의 대상이 아니다.
      //
      // ⚠ 포털 계약의 `onUnauthorized`(인증 끊김을 Host 에 알림) 창구는 열려 있으나 **여기에
      //   배선하지 않았다.** Host 가 그 통지를 받아 스스로 재로그인을 시작하면 우리 이동과
      //   경쟁해 어느 쪽이 이기는지 정해지지 않는다. 순서 규약을 포털과 확정한 뒤 배선한다.
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
