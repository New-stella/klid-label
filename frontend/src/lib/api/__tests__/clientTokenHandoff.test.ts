import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MockAdapter from 'axios-mock-adapter';

import {
  clearHostTokenHandoff,
  registerHostTokenHandoff,
  type TokenHandoffGateway,
} from '@/features/auth/tokenHandoff';
import { useAuthStore } from '@/stores/useAuthStore';

import { apiClient } from '../client';

/**
 * 요청 인터셉터가 **인계 창구를 거쳐** 토큰을 조달하는지에 대한 회귀 가드.
 *
 * 내부(관제) 채널의 동작 무변경은 기존 `client.test.ts` 가 이미 고정한다(스토어에 값을 넣고
 * `Bearer` 헤더를 단언). 여기서는 그 가드가 볼 수 없는 축 — **포털 채널** — 만 본다.
 */

const HOST_JWT = 'host-jwt-1';
const HOST_JWT_RENEWED = 'host-jwt-2';
const STALE_STORE_JWT = 'stale-store-jwt';

function makeHostGateway(initial: string | null) {
  const state = { value: initial };
  const gateway: TokenHandoffGateway = {
    getAccessToken: () => state.value,
    refresh: async () => state.value,
    onUnauthorized: vi.fn(),
    notifyActivity: vi.fn(),
  };
  return { gateway, state };
}

describe('apiClient — 포털 채널 토큰 조달', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    clearHostTokenHandoff();
    useAuthStore.setState({ token: null, claims: null });
    vi.stubEnv('VITE_BUILD_CHANNEL', 'portal');
  });

  afterEach(() => {
    mock.restore();
    clearHostTokenHandoff();
    vi.unstubAllEnvs();
  });

  function captureAuthHeader() {
    const seen: { value?: string } = {};
    mock.onGet('/echo').reply((cfg) => {
      seen.value = cfg.headers?.Authorization as string | undefined;
      return [200, { success: true, data: { ok: true }, message: null, errorCode: null }];
    });
    return seen;
  }

  it('Host_창구에서_얻은_값을_요청에_싣는다', async () => {
    const { gateway } = makeHostGateway(HOST_JWT);
    registerHostTokenHandoff(gateway);
    const seen = captureAuthHeader();

    await apiClient.get('/echo');

    expect(seen.value).toBe(`Bearer ${HOST_JWT}`);
  });

  /**
   * ★ 이 가드가 잡는 변이는 「인터셉터가 다시 스토어를 직접 읽는다」다.
   *
   * 그렇게 되돌리면 포털 채널에서 Host 가 세션을 갱신한 뒤에도 우리 스토어에 남은 옛 값을
   * 계속 실어 보내 전 API 가 401 이 된다. 그 파손은 갱신이 일어날 만큼 오래 머문 뒤에만
   * 드러나므로 다른 어떤 시험도 잡지 못한다.
   */
  it('★Host_창구가_없으면_스토어에_값이_있어도_헤더를_달지_않는다', async () => {
    useAuthStore.setState({ token: STALE_STORE_JWT, claims: null });
    const seen = captureAuthHeader();

    await apiClient.get('/echo');

    expect(seen.value).toBeUndefined();
  });

  it('Host가_갱신하면_다음_요청부터_새_값이_실린다', async () => {
    const { gateway, state } = makeHostGateway(HOST_JWT);
    registerHostTokenHandoff(gateway);
    const seen = captureAuthHeader();

    await apiClient.get('/echo');
    expect(seen.value).toBe(`Bearer ${HOST_JWT}`);

    state.value = HOST_JWT_RENEWED;
    await apiClient.get('/echo');

    expect(seen.value).toBe(`Bearer ${HOST_JWT_RENEWED}`);
  });
});
