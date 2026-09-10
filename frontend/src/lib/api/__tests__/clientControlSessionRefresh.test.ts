import MockAdapter from 'axios-mock-adapter';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  CONTROL_TOKENS_PATH,
  controlSessionHttp,
} from '@/features/auth/controlSession';
import {
  makeAccessJwt,
  makeRefreshJwt,
  putControlSession,
  signInTab,
  stubUpstreamRedirect,
} from '@/features/auth/__tests__/controlSessionFixture';
import {
  clearHostTokenHandoff,
  registerHostTokenHandoff,
} from '@/features/auth/tokenHandoff';
import { useAuthStore } from '@/stores/useAuthStore';

import { apiClient } from '../client';
import { ApiError } from '../errors';

/**
 * [@design ADR-012] [@design API-247] [@design AC-1104] [@design AC-1106]
 * 요청 인터셉터의 관제 세션 연장 — 선제 갱신 · 401 뒤 1회 재시도 · 결말 규칙 회귀 가드.
 */
describe('apiClient — 관제 채널 세션 연장', () => {
  let api: MockAdapter;
  let relay: MockAdapter;
  let redirect: ReturnType<typeof stubUpstreamRedirect>;

  beforeEach(() => {
    localStorage.clear();
    api = new MockAdapter(apiClient);
    relay = new MockAdapter(controlSessionHttp);
    vi.stubEnv('VITE_BUILD_CHANNEL', 'control');
    redirect = stubUpstreamRedirect();
  });

  afterEach(() => {
    api.restore();
    relay.restore();
    redirect.restore();
    clearHostTokenHandoff();
    vi.unstubAllEnvs();
    useAuthStore.getState().clear();
  });

  const OK = { success: true, data: { ok: true }, message: null, errorCode: null };
  const UNAUTHORIZED = { success: false, data: null, message: '인증이 필요합니다.', errorCode: 'UNAUTHORIZED' };

  /** 이 탭이 관제 세션으로 로그인해 있다 — 남은 시간 `lifetimeSec`. */
  function controlTab(lifetimeSec: number) {
    const session = makeAccessJwt(lifetimeSec);
    putControlSession(session, makeRefreshJwt());
    signInTab(session);
    return session;
  }

  function echoCapturingAuth() {
    const seen: string[] = [];
    api.onGet('/echo').reply((cfg) => {
      seen.push(String(cfg.headers?.Authorization));
      return [200, OK];
    });
    return seen;
  }

  function replyRenewed(session: string, refresh = makeRefreshJwt()) {
    relay.onPost(CONTROL_TOKENS_PATH).reply(201, {
      success: true,
      data: { sessionToken: session, refreshToken: refresh },
      message: null,
      errorCode: null,
    });
  }

  it('★다른_탭이_토큰을_바꾸면_새로고침_없이_다음_요청이_새_토큰을_싣는다', async () => {
    controlTab(1800);
    const seen = echoCapturingAuth();
    const renewed = makeAccessJwt(1800);
    putControlSession(renewed, makeRefreshJwt());

    await apiClient.get('/echo');

    expect(seen).toEqual([`Bearer ${renewed}`]);
    expect(relay.history.post).toHaveLength(0);
  });

  it('★남은_시간_10분_이하면_선제_갱신_1회_후_동시_요청_N건이_모두_새_토큰으로_나간다', async () => {
    controlTab(5 * 60);
    const seen = echoCapturingAuth();
    const renewed = makeAccessJwt(1800);
    replyRenewed(renewed);

    await Promise.all([apiClient.get('/echo'), apiClient.get('/echo'), apiClient.get('/echo')]);

    expect(relay.history.post).toHaveLength(1);
    expect(seen).toEqual([`Bearer ${renewed}`, `Bearer ${renewed}`, `Bearer ${renewed}`]);
  });

  it('남은_시간이_넉넉하면_갱신하지_않는다', async () => {
    const session = controlTab(25 * 60);
    const seen = echoCapturingAuth();

    await apiClient.get('/echo');

    expect(relay.history.post).toHaveLength(0);
    expect(seen).toEqual([`Bearer ${session}`]);
  });

  it('★401이면_갱신한_뒤_원_요청을_1회만_재시도하고_성공하면_로그아웃하지_않는다', async () => {
    controlTab(25 * 60);
    const renewed = makeAccessJwt(1800);
    replyRenewed(renewed);
    const seen: string[] = [];
    api.onGet('/secure').replyOnce((cfg) => {
      seen.push(String(cfg.headers?.Authorization));
      return [401, UNAUTHORIZED];
    });
    api.onGet('/secure').reply((cfg) => {
      seen.push(String(cfg.headers?.Authorization));
      return [200, OK];
    });

    const res = await apiClient.get('/secure');

    expect(res.data).toEqual({ ok: true });
    expect(relay.history.post).toHaveLength(1);
    expect(seen).toHaveLength(2);
    expect(seen[1]).toBe(`Bearer ${renewed}`);
    expect(redirect.assign).not.toHaveBeenCalled();
    expect(useAuthStore.getState().token).toBe(renewed);
  });

  it('★재시도도_401이면_되풀이하지_않고_로그아웃_이동하되_저장소_토큰_키는_남긴다', async () => {
    controlTab(25 * 60);
    const renewed = makeAccessJwt(1800);
    replyRenewed(renewed);
    api.onGet('/secure').reply(401, UNAUTHORIZED);

    await expect(apiClient.get('/secure')).rejects.toBeInstanceOf(ApiError);

    expect(relay.history.post).toHaveLength(1);
    expect(api.history.get).toHaveLength(2);
    expect(useAuthStore.getState().token).toBeNull();
    expect(redirect.assign).toHaveBeenCalledTimes(1);
    // 거절이 아니다 — 방금 저장한 새 토큰 쌍은 관제에서 유효한 채다. 관제 탭까지 끊지 않는다.
    expect(localStorage.getItem('klid-jwt-token')).toBe(renewed);
    expect(localStorage.getItem('tokenInfo')).not.toBeNull();
  });

  it('★401_뒤_갱신이_거절되면_즉시_로그아웃하고_공유_저장소의_토큰_키도_지운다', async () => {
    controlTab(25 * 60);
    relay.onPost(CONTROL_TOKENS_PATH).reply(401, {
      success: false, data: null, message: 'x', errorCode: 'CONTROL_SESSION_REJECTED',
    });
    api.onGet('/secure').reply(401, UNAUTHORIZED);

    await expect(apiClient.get('/secure')).rejects.toBeInstanceOf(ApiError);

    expect(api.history.get).toHaveLength(1);
    expect(redirect.assign).toHaveBeenCalledTimes(1);
    expect(localStorage.getItem('klid-jwt-token')).toBeNull();
    expect(localStorage.getItem('tokenInfo')).toBeNull();
  });

  it('★401_뒤_갱신이_일시_장애면_이_탭만_로그아웃하고_저장소_토큰_키는_남긴다', async () => {
    const session = controlTab(25 * 60);
    relay.onPost(CONTROL_TOKENS_PATH).reply(503, {
      success: false, data: null, message: 'x', errorCode: 'CONTROL_SESSION_UNAVAILABLE',
    });
    api.onGet('/secure').reply(401, UNAUTHORIZED);

    await expect(apiClient.get('/secure')).rejects.toBeInstanceOf(ApiError);

    expect(redirect.assign).toHaveBeenCalledTimes(1);
    // 관제 탭의 세션은 멀쩡할 수 있다 — 여기서 지우면 남의 세션까지 끊는다.
    expect(localStorage.getItem('klid-jwt-token')).toBe(session);
    expect(localStorage.getItem('tokenInfo')).not.toBeNull();
  });

  it('★선제_갱신이_거절되면_공유_저장소의_토큰_키도_지운다', async () => {
    controlTab(5 * 60);
    relay.onPost(CONTROL_TOKENS_PATH).reply(401, {
      success: false, data: null, message: 'x', errorCode: 'CONTROL_SESSION_REJECTED',
    });
    api.onGet('/echo').reply(200, OK);

    await apiClient.get('/echo').catch(() => undefined);

    expect(localStorage.getItem('klid-jwt-token')).toBeNull();
    expect(localStorage.getItem('tokenInfo')).toBeNull();
  });

  /**
   * [@design API-247] [@design AC-1106]
   * 관제가 새 refresh 를 주지 않은 갱신 — 새 access 로 요청은 나가되 **그 뒤 갱신은 0 건**이다.
   * (구 동작: 일시 장애로 오판해 남은 시간 내내 요청마다 갱신 중계를 되풀이했다.)
   */
  it('★새_refresh가_없는_201이면_새_토큰으로_보내고_그_뒤_요청은_갱신을_되풀이하지_않는다', async () => {
    controlTab(5 * 60);
    const seen = echoCapturingAuth();
    const renewed = makeAccessJwt(5 * 60); // 여전히 선제 갱신 창(10분) 안이다
    relay.onPost(CONTROL_TOKENS_PATH).reply(201, {
      success: true,
      data: { sessionToken: renewed, refreshToken: null },
      message: null,
      errorCode: null,
    });

    await apiClient.get('/echo');
    await apiClient.get('/echo');
    await apiClient.get('/echo');

    expect(relay.history.post).toHaveLength(1);
    expect(seen).toEqual([`Bearer ${renewed}`, `Bearer ${renewed}`, `Bearer ${renewed}`]);
    expect(redirect.assign).not.toHaveBeenCalled();
  });

  it('★skipAuthRedirect_요청은_401이어도_갱신·재시도·로그아웃_대상이_아니다', async () => {
    const session = controlTab(5 * 60); // 선제 갱신 조건에 걸려도
    replyRenewed(makeAccessJwt(1800));
    api.onPost('/admin/password-check').reply(401, {
      success: false, data: null, message: '패스워드가 올바르지 않습니다.', errorCode: 'UNAUTHORIZED',
    });

    await expect(
      apiClient.post('/admin/password-check', {}, { skipAuthRedirect: true }),
    ).rejects.toBeInstanceOf(ApiError);

    expect(relay.history.post).toHaveLength(0);
    expect(api.history.post).toHaveLength(1);
    expect(redirect.assign).not.toHaveBeenCalled();
    expect(useAuthStore.getState().token).toBe(session);
  });

  it('★선제_갱신이_거절되면_요청을_보내지_않고_즉시_로그아웃한다', async () => {
    controlTab(5 * 60);
    relay.onPost(CONTROL_TOKENS_PATH).reply(401, {
      success: false, data: null, message: 'x', errorCode: 'CONTROL_SESSION_REJECTED',
    });
    api.onGet('/echo').reply(200, OK);

    const failure = await apiClient.get('/echo').catch((e: unknown) => e);

    expect(failure).toBeInstanceOf(ApiError);
    expect((failure as ApiError).errorCode).toBe('CONTROL_SESSION_REJECTED');
    expect(api.history.get).toHaveLength(0);
    expect(redirect.assign).toHaveBeenCalledTimes(1);
  });

  it('선제_갱신이_일시_장애면_현재_토큰으로_보내고_로그아웃하지_않는다', async () => {
    const session = controlTab(5 * 60);
    relay.onPost(CONTROL_TOKENS_PATH).reply(503, {
      success: false, data: null, message: 'x', errorCode: 'CONTROL_SESSION_UNAVAILABLE',
    });
    const seen = echoCapturingAuth();

    await apiClient.get('/echo');

    expect(seen).toEqual([`Bearer ${session}`]);
    expect(redirect.assign).not.toHaveBeenCalled();
  });

  it('★개발_로그인(tokenInfo_없음)은_갱신_없이_종전대로_동작한다', async () => {
    const devSession = makeAccessJwt(5 * 60);
    localStorage.setItem('klid-jwt-token', devSession);
    signInTab(devSession);
    const seen = echoCapturingAuth();
    api.onGet('/secure').reply(401, UNAUTHORIZED);

    await apiClient.get('/echo');
    await expect(apiClient.get('/secure')).rejects.toBeInstanceOf(ApiError);

    expect(seen).toEqual([`Bearer ${devSession}`]);
    expect(relay.history.post).toHaveLength(0);
    expect(redirect.assign).toHaveBeenCalledTimes(1);
  });

  it('★포털_채널은_선제_갱신도_401_뒤_갱신도_하지_않는다', async () => {
    vi.stubEnv('VITE_BUILD_CHANNEL', 'portal');
    const hostJwt = makeAccessJwt(5 * 60);
    // 최악의 형상 — 포털 스토어가 Host 토큰의 거울을 들고 있고, 저장소에도 그 토큰과 짝인 갱신 정보가
    // 남아 있다. 창구 판정이 빠지면 곧바로 선제 갱신·401 뒤 갱신이 나가는 조건이다.
    putControlSession(hostJwt, makeRefreshJwt());
    signInTab(hostJwt);
    registerHostTokenHandoff({
      getAccessToken: () => hostJwt,
      refresh: async () => hostJwt,
      onUnauthorized: vi.fn(),
      notifyActivity: vi.fn(),
    });
    api.onGet('/echo').reply(200, OK);
    api.onGet('/secure').reply(401, UNAUTHORIZED);

    await apiClient.get('/echo');
    await apiClient.get('/secure').catch(() => undefined);

    expect(relay.history.post).toHaveLength(0);
    expect(relay.history.delete).toHaveLength(0);
    expect(api.history.get[0].headers?.['x-access-token']).toBe(hostJwt);
  });

  it('★브라우저는_관제_계정_주소로_요청하지_않는다', async () => {
    controlTab(5 * 60);
    replyRenewed(makeAccessJwt(1800));
    api.onGet('/echo').reply(200, OK);

    await apiClient.get('/echo');

    const urls = [...relay.history.post, ...api.history.get].map(
      (c) => `${c.baseURL ?? ''}${c.url ?? ''}`,
    );
    expect(urls.length).toBeGreaterThan(0);
    expect(urls.every((u) => !u.includes('/api/account'))).toBe(true);
  });
});
