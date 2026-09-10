import MockAdapter from 'axios-mock-adapter';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { Role } from '@/lib/api/types';
import { useAuthStore } from '@/stores/useAuthStore';

import {
  CONTROL_SESSION_PATH,
  CONTROL_TOKENS_PATH,
  TOKEN_INFO_FIELDS,
  buildControlTokenInfo,
  controlSessionHttp,
  getControlSessionTiming,
  installControlSessionStorageSync,
  refreshControlSession,
  requestControlLogout,
  saveControlTokens,
  syncControlAccessToken,
} from '../controlSession';

import {
  fireStorage,
  makeAccessJwt,
  makeRefreshJwt,
  putControlSession,
  referenceControlTokenInfoJson,
  signInTab,
  stubUpstreamRedirect,
} from './controlSessionFixture';

/**
 * [@design INT-013] [@design API-247] [@design API-246] [@design AC-1104] [@design AC-1106]
 * 관제 채널 세션 연장 — 저장 형식 · 갱신 중계 호출 계약 · 탭 간 추종의 회귀 가드.
 */
describe('controlSession — 관제 채널 세션 연장', () => {
  let relay: MockAdapter;

  beforeEach(() => {
    // 전역 준비는 저장소를 비우지 않는다 — 앞 시험의 `tokenInfo` 가 남으면 짝 판정이 흐려진다.
    localStorage.clear();
    relay = new MockAdapter(controlSessionHttp);
    vi.stubEnv('VITE_BUILD_CHANNEL', 'control');
  });

  afterEach(() => {
    relay.restore();
    vi.unstubAllEnvs();
    useAuthStore.getState().clear();
  });

  function replyRenewed(session: string, refresh: string) {
    relay.onPost(CONTROL_TOKENS_PATH).reply(201, {
      success: true,
      data: { sessionToken: session, refreshToken: refresh },
      message: null,
      errorCode: null,
    });
  }

  describe('저장 형식 — 관제 saveTokens 와 글자 그대로', () => {
    it('tokenInfo_13필드의_이름과_순서가_관제와_같다', () => {
      const session = makeAccessJwt(1800);
      expect(Object.keys(buildControlTokenInfo(session, makeRefreshJwt()))).toEqual([
        ...TOKEN_INFO_FIELDS,
      ]);
      expect(TOKEN_INFO_FIELDS).toHaveLength(13);
    });

    it('★저장한_tokenInfo_문자열이_관제_saveTokens_기준_재현과_완전히_같다', () => {
      const session = makeAccessJwt(1800);
      const refresh = makeRefreshJwt();
      saveControlTokens(session, refresh);

      expect(localStorage.getItem('tokenInfo')).toBe(referenceControlTokenInfoJson(session, refresh));
      expect(localStorage.getItem('klid-jwt-token')).toBe(session);
      expect(localStorage.getItem('userNm')).toBe('시스템관리자');
    });

    it('만료·발급_시각은_밀리초이고_접근불가_메뉴가_없으면_빈_배열이다', () => {
      const session = makeAccessJwt(1800, { inaccessible_menus: undefined });
      const info = buildControlTokenInfo(session, makeRefreshJwt());
      const claims = JSON.parse(atob(session.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')));

      expect(info.expiresAt).toBe(claims.exp * 1000);
      expect(info.issuedAt).toBe(claims.iat * 1000);
      expect(info.inAccessibleMenus).toEqual([]);
    });
  });

  describe('갱신 중계 호출 — API-247', () => {
    it('★저작도구_갱신_창구에_refresh_토큰을_본문으로만_싣고_인증_헤더를_붙이지_않는다', async () => {
      const session = makeAccessJwt(300);
      const refresh = makeRefreshJwt();
      putControlSession(session, refresh);
      signInTab(session);
      replyRenewed(makeAccessJwt(1800), makeRefreshJwt());

      await refreshControlSession();

      expect(relay.history.post).toHaveLength(1);
      const call = relay.history.post[0];
      expect(call.url).toBe('/auth/control-tokens');
      expect(JSON.parse(call.data as string)).toEqual({ refreshToken: refresh });
      const headerNames = Object.keys(call.headers ?? {}).map((k) => k.toLowerCase());
      expect(headerNames).not.toContain('authorization');
      expect(headerNames).not.toContain('x-access-token');
      // 브라우저는 관제 계정 서비스를 직접 부르지 않는다.
      expect(`${call.baseURL ?? ''}${call.url}`).not.toContain('/api/account');
    });

    it('201이면_새_토큰_쌍을_관제_형식으로_저장하고_같은_사용자는_역할과_확보상태를_보존한다', async () => {
      const session = makeAccessJwt(300);
      putControlSession(session, makeRefreshJwt());
      signInTab(session);
      const renewed = makeAccessJwt(1800, { sessionId: 'S-1' });
      const renewedRefresh = makeRefreshJwt();
      replyRenewed(renewed, renewedRefresh);

      const outcome = await refreshControlSession();

      expect(outcome).toEqual({ kind: 'refreshed', token: renewed });
      expect(localStorage.getItem('klid-jwt-token')).toBe(renewed);
      expect(localStorage.getItem('tokenInfo')).toBe(
        referenceControlTokenInfoJson(renewed, renewedRefresh),
      );
      const state = useAuthStore.getState();
      expect(state.token).toBe(renewed);
      expect(state.claims?.role).toBe(Role.ADMIN);
      expect(state.claims?.name).toBe('홍길동');
      expect(state.serverRoleStatus).toBe('ready');
    });

    it('한_탭의_동시_갱신_N건은_호출_1회를_공유한다', async () => {
      const session = makeAccessJwt(300);
      putControlSession(session, makeRefreshJwt());
      signInTab(session);
      replyRenewed(makeAccessJwt(1800), makeRefreshJwt());

      await Promise.all([refreshControlSession(), refreshControlSession(), refreshControlSession()]);

      expect(relay.history.post).toHaveLength(1);
    });

    it('★다른_탭이_이미_갱신했으면_갱신_창구를_호출하지_않고_저장소의_새_값을_쓴다', async () => {
      const session = makeAccessJwt(300);
      putControlSession(session, makeRefreshJwt());
      signInTab(session);
      syncControlAccessToken(); // 이 탭이 지금 짝을 쥔다
      const otherTabSession = makeAccessJwt(1800);
      putControlSession(otherTabSession, makeRefreshJwt()); // 다른 탭의 갱신
      replyRenewed(makeAccessJwt(1800), makeRefreshJwt());

      const outcome = await refreshControlSession();

      expect(relay.history.post).toHaveLength(0);
      expect(outcome).toEqual({ kind: 'adopted', token: otherTabSession });
      expect(useAuthStore.getState().token).toBe(otherTabSession);
    });

    it('401이면_거절이고_같은_refresh_토큰으로_다시_부르지_않는다', async () => {
      const session = makeAccessJwt(300);
      putControlSession(session, makeRefreshJwt());
      signInTab(session);
      relay.onPost(CONTROL_TOKENS_PATH).reply(401, {
        success: false,
        data: null,
        message: '관제 세션이 만료되었습니다. 다시 로그인해 주세요.',
        errorCode: 'CONTROL_SESSION_REJECTED',
      });

      expect(await refreshControlSession()).toEqual({ kind: 'rejected' });
      expect(await refreshControlSession()).toEqual({ kind: 'rejected' });
      expect(relay.history.post).toHaveLength(1);
    });

    it('★거절_직후_저장소를_다시_읽어_다른_탭이_갱신했으면_그_토큰으로_계속한다', async () => {
      const session = makeAccessJwt(300);
      putControlSession(session, makeRefreshJwt());
      signInTab(session);
      const otherTabSession = makeAccessJwt(1800);
      relay.onPost(CONTROL_TOKENS_PATH).reply(() => {
        putControlSession(otherTabSession, makeRefreshJwt()); // 그사이 다른 탭이 갱신
        return [401, { success: false, data: null, message: 'x', errorCode: 'CONTROL_SESSION_REJECTED' }];
      });

      const outcome = await refreshControlSession();

      expect(outcome).toEqual({ kind: 'adopted', token: otherTabSession });
      expect(useAuthStore.getState().token).toBe(otherTabSession);
    });

    it('503이면_일시_장애로_보고_저장소에_아무것도_쓰지_않는다', async () => {
      const session = makeAccessJwt(300);
      const refresh = makeRefreshJwt();
      putControlSession(session, refresh);
      signInTab(session);
      const before = localStorage.getItem('tokenInfo');
      relay.onPost(CONTROL_TOKENS_PATH).reply(503, {
        success: false,
        data: null,
        message: '관제 서버에 연결할 수 없습니다.',
        errorCode: 'CONTROL_SESSION_UNAVAILABLE',
      });

      expect(await refreshControlSession()).toEqual({ kind: 'unavailable', token: session });
      expect(localStorage.getItem('tokenInfo')).toBe(before);
      expect(localStorage.getItem('klid-jwt-token')).toBe(session);
    });

    it('서버에_닿지_못해도_일시_장애다', async () => {
      const session = makeAccessJwt(300);
      putControlSession(session, makeRefreshJwt());
      signInTab(session);
      relay.onPost(CONTROL_TOKENS_PATH).networkError();

      expect(await refreshControlSession()).toEqual({ kind: 'unavailable', token: session });
    });

    /**
     * [@design API-247] [@design AC-1106]
     * 관제가 `error 0` 과 새 access 는 주면서 새 refresh 를 주지 않은 갱신 — 서버는 201 +
     * `refreshToken: null` 을 낸다. **성공 판정의 필수 조건은 `sessionToken` 하나다.**
     */
    describe('새 refresh 토큰이 없는 201 — 성공이되 더 갱신하지 않는 세션', () => {
      function replyRenewedWithoutRefresh(session: string, refreshToken: unknown = null) {
        relay.onPost(CONTROL_TOKENS_PATH).reply(201, {
          success: true,
          data: { sessionToken: session, refreshToken },
          message: null,
          errorCode: null,
        });
      }

      it('★201에_refresh가_null이면_성공이고_새_access를_저장하되_refresh_칸을_비운다', async () => {
        const session = makeAccessJwt(300);
        putControlSession(session, makeRefreshJwt());
        signInTab(session);
        const renewed = makeAccessJwt(1800);
        replyRenewedWithoutRefresh(renewed);

        // 일시 장애가 아니라 갱신 성공이다 — 쓸 수 있는 새 access 토큰을 버리지 않는다.
        expect(await refreshControlSession()).toEqual({ kind: 'refreshed', token: renewed });
        expect(localStorage.getItem('klid-jwt-token')).toBe(renewed);
        expect(useAuthStore.getState().token).toBe(renewed);
        // 관제 `saveTokens(session, undefined)` 와 글자 그대로 같다 — 키 자체가 빠진다.
        expect(localStorage.getItem('tokenInfo')).toBe(referenceControlTokenInfoJson(renewed));
        const info = JSON.parse(localStorage.getItem('tokenInfo') as string) as object;
        expect(Object.prototype.hasOwnProperty.call(info, 'refresh_token')).toBe(false);
      });

      it('★그_뒤로는_갱신_창구를_한_번도_부르지_않는다_요청마다_되풀이하지_않는다', async () => {
        const session = makeAccessJwt(300);
        putControlSession(session, makeRefreshJwt());
        signInTab(session);
        replyRenewedWithoutRefresh(makeAccessJwt(1800));

        const first = await refreshControlSession();
        expect(first.kind).toBe('refreshed');

        // 남은 시간이 아무리 짧아도 다시 부르지 않는다 — 갱신할 수단이 없는 세션이다.
        expect((await refreshControlSession()).kind).toBe('skipped');
        expect((await refreshControlSession()).kind).toBe('skipped');
        expect(relay.history.post).toHaveLength(1);
        // 팝업·선제 갱신의 게이트도 함께 닫힌다.
        expect(getControlSessionTiming()?.refreshable).toBe(false);
      });

      it('refresh가_빈_문자열이어도_없는_것으로_본다', async () => {
        const session = makeAccessJwt(300);
        putControlSession(session, makeRefreshJwt());
        signInTab(session);
        const renewed = makeAccessJwt(1800);
        replyRenewedWithoutRefresh(renewed, '   ');

        expect((await refreshControlSession()).kind).toBe('refreshed');
        expect(localStorage.getItem('tokenInfo')).toBe(referenceControlTokenInfoJson(renewed));
      });
    });

    it('★개발_로그인(tokenInfo_없음)은_갱신하지_않고_현재_토큰을_돌려준다', async () => {
      const devSession = makeAccessJwt(3600);
      localStorage.setItem('klid-jwt-token', devSession);
      signInTab(devSession);

      expect(await refreshControlSession()).toEqual({ kind: 'skipped', token: devSession });
      expect(relay.history.post).toHaveLength(0);
    });

    it('남의_access_토큰과_짝인_tokenInfo는_쓰지_않는다_개발_토큰을_남의_갱신으로_덮지_않는다', async () => {
      putControlSession(makeAccessJwt(300), makeRefreshJwt()); // 앞선 관제 세션의 잔재
      const devSession = makeAccessJwt(3600);
      localStorage.setItem('klid-jwt-token', devSession);
      signInTab(devSession);

      expect((await refreshControlSession()).kind).toBe('skipped');
      expect(relay.history.post).toHaveLength(0);
    });
  });

  describe('로그아웃 중계 — API-246', () => {
    it('access_토큰을_Bearer로_실어_로그아웃_창구를_부른다', async () => {
      const session = makeAccessJwt(300);
      relay.onDelete(CONTROL_SESSION_PATH).reply(204);

      await requestControlLogout(session);

      expect(relay.history.delete).toHaveLength(1);
      expect(relay.history.delete[0].url).toBe('/auth/control-session');
      expect(relay.history.delete[0].headers?.Authorization).toBe(`Bearer ${session}`);
    });

    it('실패해도_던지지_않는다_결말은_응답과_무관하다', async () => {
      relay.onDelete(CONTROL_SESSION_PATH).networkError();
      await expect(requestControlLogout(makeAccessJwt(300))).resolves.toBeUndefined();
    });
  });

  describe('현재값 추종', () => {
    it('★스토어와_저장소가_다르면_저장소_현재값을_돌려주고_스토어를_맞춘다', () => {
      const session = makeAccessJwt(300);
      signInTab(session);
      const renewed = makeAccessJwt(1800);
      localStorage.setItem('klid-jwt-token', renewed);

      expect(syncControlAccessToken()).toBe(renewed);
      expect(useAuthStore.getState().token).toBe(renewed);
      expect(useAuthStore.getState().serverRoleStatus).toBe('ready');
    });

    it('세션이_없으면_저장소_값으로_세션을_세우지_않는다', () => {
      localStorage.setItem('klid-jwt-token', makeAccessJwt(1800));
      expect(syncControlAccessToken()).toBeNull();
      expect(useAuthStore.getState().token).toBeNull();
    });

    it('저장소_값이_형식_검사를_통과하지_못하면_스토어_값을_그대로_쓴다', () => {
      const session = makeAccessJwt(300);
      signInTab(session);
      localStorage.setItem('klid-jwt-token', 'not-a-jwt');
      expect(syncControlAccessToken()).toBe(session);
    });

    it('만료는_tokenInfo를_우선하고_임계는_tokenInfo_클레임_30분_순이다', () => {
      const session = makeAccessJwt(600, { sessionExpAlarm: undefined, sessionTime: undefined });
      signInTab(session);
      const claims = JSON.parse(atob(session.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')));
      expect(getControlSessionTiming()).toMatchObject({
        expiresAt: claims.exp * 1000,
        alarmMs: 30 * 60 * 1000,
        sessionTimeMinutes: 30,
        refreshable: false,
      });

      putControlSession(session, makeRefreshJwt());
      const info = JSON.parse(localStorage.getItem('tokenInfo') as string);
      localStorage.setItem(
        'tokenInfo',
        JSON.stringify({ ...info, expiresAt: 123_000, sessionExpAlarm: '5' }),
      );
      expect(getControlSessionTiming()).toMatchObject({
        expiresAt: 123_000,
        alarmMs: 5 * 60 * 1000,
        refreshable: true,
      });
    });
  });

  describe('탭 동기화 — storage 이벤트', () => {
    it('다른_탭이_토큰을_바꾸면_스토어를_맞추고_알린다', () => {
      const session = makeAccessJwt(300);
      signInTab(session);
      const onChange = vi.fn();
      const off = installControlSessionStorageSync(onChange);
      const renewed = makeAccessJwt(1800);
      localStorage.setItem('klid-jwt-token', renewed);

      fireStorage('klid-jwt-token', session, renewed);

      expect(useAuthStore.getState().token).toBe(renewed);
      expect(onChange).toHaveBeenCalled();
      off();
    });

    it('★토큰_키가_삭제되면_로그아웃_이동한다', () => {
      const redirect = stubUpstreamRedirect();
      const session = makeAccessJwt(300);
      signInTab(session);
      const off = installControlSessionStorageSync();

      fireStorage('klid-jwt-token', session, null);

      expect(useAuthStore.getState().token).toBeNull();
      expect(redirect.assign).toHaveBeenCalledTimes(1);
      off();
      redirect.restore();
    });

    it('★tokenInfo는_있다가_사라졌을_때만_로그아웃으로_본다', () => {
      const redirect = stubUpstreamRedirect();
      const session = makeAccessJwt(300);
      localStorage.setItem('klid-jwt-token', session);
      signInTab(session);
      const off = installControlSessionStorageSync();

      fireStorage('tokenInfo', null, null); // 처음부터 없던 것
      expect(redirect.assign).not.toHaveBeenCalled();
      expect(useAuthStore.getState().token).toBe(session);

      fireStorage('tokenInfo', '{"access_token":"x"}', null); // 있다가 사라짐
      expect(redirect.assign).toHaveBeenCalledTimes(1);
      expect(useAuthStore.getState().token).toBeNull();
      off();
      redirect.restore();
    });

    it('로그인_전(세션_없음)에는_아무것도_하지_않는다', () => {
      const redirect = stubUpstreamRedirect('/ingress');
      const off = installControlSessionStorageSync();
      fireStorage('klid-jwt-token', 'a.b.c', null);
      expect(redirect.assign).not.toHaveBeenCalled();
      off();
      redirect.restore();
    });
  });
});
