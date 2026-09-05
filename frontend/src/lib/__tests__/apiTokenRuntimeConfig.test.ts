// API base 와 토큰 인계 채널의 <런타임 주입> 축 가드.
//
// 이 두 값은 상위 로그인 주소만큼 급하지는 않지만(전자는 상대경로 기본값이 대부분의 현장에서
// 맞고, 후자는 채널마다 갈린다) 같은 성질의 <현장값>이라 같은 창구에 얹는다. 창구를 나누면
// 운영자가 "이 값은 어디서 고치나"를 항목마다 다시 찾아야 한다.

import { afterEach, describe, expect, it, vi } from 'vitest';

import { resolveToken } from '@/features/auth/tokenIngress';
import { RUNTIME_CONFIG_GLOBAL } from '@/lib/runtimeConfig';

function stubRuntimeConfig(value: Record<string, string>): void {
  (globalThis as Record<string, unknown>)[RUNTIME_CONFIG_GLOBAL] = value;
}

function b64url(obj: Record<string, unknown>): string {
  const b64 = btoa(unescape(encodeURIComponent(JSON.stringify(obj))));
  return b64.replace(/=+$/, '').replace(/\+/g, '-').replace(/\//g, '_');
}

const URL_TOKEN = `${b64url({ alg: 'HS256', typ: 'JWT' })}.${b64url({ sub: 'u', exp: 9999999999 })}.sig`;

afterEach(() => {
  delete (globalThis as Record<string, unknown>)[RUNTIME_CONFIG_GLOBAL];
  vi.unstubAllEnvs();
  vi.resetModules();
});

describe('토큰 인계 채널 — 런타임 주입', () => {
  it('런타임_전략이_빌드_전략을_이긴다', () => {
    // given: 빌드는 localStorage 로 구워졌는데 현장은 URL 채널이 필요한 레거시
    vi.stubEnv('VITE_TOKEN_INGRESS', 'localStorage');
    stubRuntimeConfig({ VITE_TOKEN_INGRESS: 'url' });

    // when / then
    expect(resolveToken({ urlToken: URL_TOKEN, cookieName: 'klid_jwt' })).toBe(URL_TOKEN);
  });

  it('런타임_전략이_오타면_안전한_기본값으로_떨어진다', () => {
    // fail-closed — 해석 못 하는 값이 URL 채널을 열면 안 된다.
    stubRuntimeConfig({ VITE_TOKEN_INGRESS: 'localstorage' });
    expect(resolveToken({ urlToken: URL_TOKEN, cookieName: 'klid_jwt' })).toBeNull();
  });

  it('런타임_미지정이면_빌드_전략이_그대로_쓰인다', () => {
    vi.stubEnv('VITE_TOKEN_INGRESS', 'url');
    expect(resolveToken({ urlToken: URL_TOKEN, cookieName: 'klid_jwt' })).toBe(URL_TOKEN);
  });
});

describe('API base — 런타임 주입', () => {
  it('런타임_값이_빌드_값을_이긴다', async () => {
    // axios 인스턴스는 모듈 로드 시점에 baseURL 을 굳힌다 — 생성물 스크립트가 번들보다
    // <먼저> 실행되는 실제 순서를 재현하려면 전역을 심은 뒤 모듈을 새로 불러야 한다.
    vi.stubEnv('VITE_API_BASE_URL', '/api/v1');
    stubRuntimeConfig({ VITE_API_BASE_URL: 'https://api.site.internal/api/v1' });

    vi.resetModules();
    const { apiClient } = await import('@/lib/api/client');

    expect(apiClient.defaults.baseURL).toBe('https://api.site.internal/api/v1');
  });

  it('런타임_미지정이면_빌드_기본값이_유지된다', async () => {
    vi.stubEnv('VITE_API_BASE_URL', '/api/v1');

    vi.resetModules();
    const { apiClient } = await import('@/lib/api/client');

    expect(apiClient.defaults.baseURL).toBe('/api/v1');
  });
});
