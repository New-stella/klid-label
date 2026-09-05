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
 * [@design INT-013] 요청 인터셉터의 **토큰 조달 경로**와 **인증 헤더 계약** 회귀 가드.
 *
 * 두 축은 짝이다 — 조달이 옳아도 싣는 자리가 틀리면 서버가 토큰을 못 찾고, 그 실패는 빌드가
 * 아니라 런타임 401 로만 드러난다.
 *
 * 내부(관제) 채널의 조달 무변경은 기존 `client.test.ts` 도 고정한다(스토어에 값을 넣고
 * `Bearer` 헤더를 단언). 여기서는 그 가드가 볼 수 없는 축 — **채널이 갈리는 헤더** — 을 본다.
 */

const HOST_JWT = 'host-jwt-1';
const HOST_JWT_RENEWED = 'host-jwt-2';
const STALE_STORE_JWT = 'stale-store-jwt';
const CONTROL_JWT = 'control-jwt-1';

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

  /**
   * 요청이 실제로 들고 나간 헤더를 **둘 다** 본다.
   *
   * 한쪽만 보면 「전용 헤더가 붙었다」는 확인해도 「반대 채널 헤더가 함께 나갔다」를 못 잡는다 —
   * 서버는 두 헤더가 함께 오고 값이 다르면 401 이므로, 그것이 정확히 잡아야 할 결함이다.
   */
  function captureAuthHeaders() {
    const seen: { authorization?: string; xAccessToken?: string; names: string[] } = { names: [] };
    mock.onGet('/echo').reply((cfg) => {
      const h = (cfg.headers ?? {}) as Record<string, unknown>;
      seen.authorization = h['Authorization'] as string | undefined;
      seen.xAccessToken = h['x-access-token'] as string | undefined;
      seen.names = Object.keys(h).filter((k) =>
        ['authorization', 'x-access-token'].includes(k.toLowerCase()),
      );
      return [200, { success: true, data: { ok: true }, message: null, errorCode: null }];
    });
    return seen;
  }

  it('Host_창구에서_얻은_값을_전용_헤더로_싣는다', async () => {
    const { gateway } = makeHostGateway(HOST_JWT);
    registerHostTokenHandoff(gateway);
    const seen = captureAuthHeaders();

    await apiClient.get('/echo');

    expect(seen.xAccessToken).toBe(HOST_JWT);
  });

  /**
   * ★ 헤더 이름을 되돌리는 변이(`Authorization` 로 복귀)를 잡는 자리다.
   *   서버는 채널을 헤더가 아니라 토큰 클레임으로 가르므로 **토큰 자체는 유효**하고,
   *   그래서 이 축이 없으면 되돌림이 다른 어떤 시험에도 걸리지 않는다.
   */
  it('★포털_채널은_Authorization_헤더를_쓰지_않는다', async () => {
    const { gateway } = makeHostGateway(HOST_JWT);
    registerHostTokenHandoff(gateway);
    const seen = captureAuthHeaders();

    await apiClient.get('/echo');

    expect(seen.authorization).toBeUndefined();
    expect(seen.names).toEqual(['x-access-token']);
  });

  /**
   * ★ `Authorization` 값을 그대로 복사해 넣는 구현을 잡는다 — 모양만 비슷한 이 변이가 전량 401 을 만든다.
   *
   * ⚠ 사유를 정확히 적는다. 전용 헤더를 **단독으로** 보내면서 접두를 붙이면 그 문자열 **전체가
   *   토큰**이 되어 **서명 파싱에서 거부**된다(401). `Authorization` 과 **함께** 올 때 나는
   *   **값 충돌** 거부는 그와 **별개 경로**다 — 두 경우 다 401 이나 사유가 다르며, 섞어 적으면
   *   「하나만 보내면 접두가 붙어도 괜찮다」는 잘못된 역추론이 나온다.
   *   근거: 백엔드 `JwtFilterPortalHeaderIngressTest` 의 두 케이스.
   */
  it('★전용_헤더_값에_Bearer_접두가_붙지_않는다', async () => {
    const { gateway } = makeHostGateway(HOST_JWT);
    registerHostTokenHandoff(gateway);
    const seen = captureAuthHeaders();

    await apiClient.get('/echo');

    expect(seen.xAccessToken).not.toContain('Bearer');
    expect(seen.xAccessToken).toBe(HOST_JWT);
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
    const seen = captureAuthHeaders();

    await apiClient.get('/echo');

    expect(seen.xAccessToken).toBeUndefined();
    expect(seen.authorization).toBeUndefined();
  });

  /**
   * ★★ 매 호출 취득 성질 — 인터셉터가 값을 한 번 잡아 두는(모듈 상수·메모이제이션·스토어
   *    복사) 변이를 잡는다. Host 가 세션을 갱신하면 그 스냅샷은 **죽은 토큰**이 되고,
   *    그 파손은 갱신이 일어날 만큼 오래 머문 뒤에만 드러나 개발 중에는 조용하다.
   */
  it('★★Host가_갱신하면_다음_요청부터_새_값이_실린다_캐시하지_않는다', async () => {
    const { gateway, state } = makeHostGateway(HOST_JWT);
    registerHostTokenHandoff(gateway);
    const seen = captureAuthHeaders();

    await apiClient.get('/echo');
    expect(seen.xAccessToken).toBe(HOST_JWT);

    state.value = HOST_JWT_RENEWED;
    await apiClient.get('/echo');

    expect(seen.xAccessToken).toBe(HOST_JWT_RENEWED);
  });

  /**
   * 401 재시도 경로도 **같은 헤더 이름**을 쓴다.
   *
   * 이 경로가 `Authorization` 을 되읽으면 포털 채널에서는 언제나 「헤더가 없다」로 읽혀
   * 이미 인증된 요청까지 한 번 더 쏘고, 그 재시도가 반대 채널 헤더를 덧붙여 **두 헤더 충돌
   * 401** 을 만든다.
   */
  it('★401_재시도도_전용_헤더로_다시_보낸다', async () => {
    const { gateway, state } = makeHostGateway(null);
    registerHostTokenHandoff(gateway);

    const attempts: Array<Record<string, unknown>> = [];
    mock.onGet('/echo').reply((cfg) => {
      const h = (cfg.headers ?? {}) as Record<string, unknown>;
      attempts.push(h);
      if (!h['x-access-token']) {
        // 1차: 토큰 적재 직전에 발사돼 헤더가 비었다. 그 사이 Host 가 토큰을 갖게 된다.
        state.value = HOST_JWT;
        return [401, { success: false, data: null, message: null, errorCode: 'UNAUTHORIZED' }];
      }
      return [200, { success: true, data: { ok: true }, message: null, errorCode: null }];
    });

    const res = await apiClient.get('/echo');

    expect(res.data).toEqual({ ok: true });
    expect(attempts).toHaveLength(2);
    expect(attempts[1]['x-access-token']).toBe(HOST_JWT);
    expect(attempts[1]['Authorization']).toBeUndefined();
  });

  /**
   * ★★ 되읽을 헤더 이름을 `'Authorization'` 리터럴로 되돌리는 변이를 잡는 **유일한** 자리다.
   *
   * 위 재시도 시험만으로는 잡히지 않는다 — 거기서는 1차 요청에 헤더가 **아예 없어서**
   * 어느 이름으로 되읽어도 결과가 같기 때문이다(실측: 그 변이가 생존했다). 구분이 생기는 것은
   * **전용 헤더를 이미 달고 나간 요청이 401 을 받은** 이 경우다. 이름이 어긋나면 언제나
   * 「헤더가 없다」로 읽혀 인증된 요청까지 한 번 더 쏘고, 그 재시도가 만료 처리를 가린다.
   */
  it('★★전용_헤더를_달고_나간_요청의_401은_재시도하지_않는다_만료로_처리된다', async () => {
    const assignSpy = vi.fn();
    const originalLocation = window.location;
    Object.defineProperty(window, 'location', {
      writable: true,
      value: { ...originalLocation, href: 'http://app.local/secure', assign: assignSpy },
    });
    vi.stubEnv('VITE_CONTROL_LOGIN_URL', 'http://control.local/login');
    vi.stubEnv('VITE_PORTAL_LOGIN_URL', 'http://portal.local/login');

    const { gateway } = makeHostGateway(HOST_JWT);
    registerHostTokenHandoff(gateway);

    const attempts: Array<Record<string, unknown>> = [];
    mock.onGet('/echo').reply((cfg) => {
      attempts.push((cfg.headers ?? {}) as Record<string, unknown>);
      return [401, { success: false, data: null, message: null, errorCode: 'UNAUTHORIZED' }];
    });

    await expect(apiClient.get('/echo')).rejects.toBeTruthy();

    // 1차에 이미 전용 헤더가 실렸으므로 race 재시도 대상이 아니다.
    expect(attempts).toHaveLength(1);
    expect(attempts[0]['x-access-token']).toBe(HOST_JWT);
    expect(assignSpy).toHaveBeenCalledTimes(1);

    Object.defineProperty(window, 'location', { writable: true, value: originalLocation });
  });
});

describe('apiClient — 내부(관제) 채널 인증 헤더 무변경', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    clearHostTokenHandoff();
    useAuthStore.setState({ token: null, claims: null });
    vi.stubEnv('VITE_BUILD_CHANNEL', 'control');
  });

  afterEach(() => {
    mock.restore();
    clearHostTokenHandoff();
    vi.unstubAllEnvs();
  });

  function captureAuthHeaders() {
    const seen: { authorization?: string; xAccessToken?: string; names: string[] } = { names: [] };
    mock.onGet('/echo').reply((cfg) => {
      const h = (cfg.headers ?? {}) as Record<string, unknown>;
      seen.authorization = h['Authorization'] as string | undefined;
      seen.xAccessToken = h['x-access-token'] as string | undefined;
      seen.names = Object.keys(h).filter((k) =>
        ['authorization', 'x-access-token'].includes(k.toLowerCase()),
      );
      return [200, { success: true, data: { ok: true }, message: null, errorCode: null }];
    });
    return seen;
  }

  /**
   * ★ 관제 채널 회귀 0 — 이 라운드가 지켜야 할 최우선 불변식이다.
   *   전용 헤더가 관제 채널로 새어 나가면 그 요청은 서버에서 두 헤더 충돌·미지 헤더 취급이 된다.
   */
  it('★Authorization_Bearer_그대로이며_전용_헤더가_새어나가지_않는다', async () => {
    useAuthStore.setState({ token: CONTROL_JWT, claims: null });
    const seen = captureAuthHeaders();

    await apiClient.get('/echo');

    expect(seen.authorization).toBe(`Bearer ${CONTROL_JWT}`);
    expect(seen.xAccessToken).toBeUndefined();
    expect(seen.names).toEqual(['Authorization']);
  });

  it('채널_미설정_빌드도_Authorization_Bearer_그대로다', async () => {
    vi.stubEnv('VITE_BUILD_CHANNEL', '');
    useAuthStore.setState({ token: CONTROL_JWT, claims: null });
    const seen = captureAuthHeaders();

    await apiClient.get('/echo');

    expect(seen.authorization).toBe(`Bearer ${CONTROL_JWT}`);
    expect(seen.xAccessToken).toBeUndefined();
  });
});
