import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { useAuthStore } from '@/stores/useAuthStore';

import { clearControlTokensIfCurrent } from '../controlSession';
import { terminateSession } from '../sessionTermination';

import {
  makeAccessJwt,
  makeRefreshJwt,
  putControlSession,
  signInTab,
  stubUpstreamRedirect,
} from './controlSessionFixture';

/**
 * [@design AC-1106] [@design ADR-012]
 * 강제 로그아웃의 **공유 저장소 뒷정리** — 언제 지우고 언제 남기는가.
 *
 * 거절·남은 시간 0 은 관제 웹과 같은 결말이라 같은 출처의 토큰 키까지 지운다(관제 탭도 함께
 * 로그아웃). 일시 장애로 이 탭만 나가는 경로는 지우지 않는다 — 관제 탭 세션은 멀쩡할 수 있다.
 */
describe('terminateSession — 공유 저장소 토큰 키 정리', () => {
  let redirect: ReturnType<typeof stubUpstreamRedirect>;

  beforeEach(() => {
    localStorage.clear();
    vi.stubEnv('VITE_BUILD_CHANNEL', 'control');
    redirect = stubUpstreamRedirect();
  });

  afterEach(() => {
    redirect.restore();
    vi.unstubAllEnvs();
    useAuthStore.getState().clear();
  });

  function controlTab(lifetimeSec = 300) {
    const session = makeAccessJwt(lifetimeSec);
    putControlSession(session, makeRefreshJwt());
    signInTab(session);
    return session;
  }

  it('기본값은_저장소_토큰_키를_지우지_않는다_기존_호출부_동작_보존', () => {
    const session = controlTab();

    terminateSession();

    expect(useAuthStore.getState().token).toBeNull();
    expect(redirect.assign).toHaveBeenCalledTimes(1);
    expect(localStorage.getItem('klid-jwt-token')).toBe(session);
    expect(localStorage.getItem('tokenInfo')).not.toBeNull();
  });

  it('★옵션을_켜면_두_키를_모두_지운다', () => {
    controlTab();

    terminateSession({ clearStoredControlTokens: true });

    expect(localStorage.getItem('klid-jwt-token')).toBeNull();
    expect(localStorage.getItem('tokenInfo')).toBeNull();
    expect(redirect.assign).toHaveBeenCalledTimes(1);
  });

  it('★지우기_직전_저장소에_다른_탭의_새_토큰이_있으면_지우지_않는다', () => {
    controlTab();
    // 이 탭이 종료 판정을 내린 사이 다른 탭(관제 탭 포함)이 갱신해 저장했다.
    const otherTabSession = makeAccessJwt(1800);
    putControlSession(otherTabSession, makeRefreshJwt());

    terminateSession({ clearStoredControlTokens: true });

    // 이 탭은 나가지만 멀쩡한 세션을 끊지 않는다.
    expect(localStorage.getItem('klid-jwt-token')).toBe(otherTabSession);
    expect(localStorage.getItem('tokenInfo')).not.toBeNull();
    expect(useAuthStore.getState().token).toBeNull();
    expect(redirect.assign).toHaveBeenCalledTimes(1);
  });

  it('포털_채널에서는_옵션을_켜도_저장소를_건드리지_않는다', () => {
    const session = controlTab();
    vi.stubEnv('VITE_BUILD_CHANNEL', 'portal');

    terminateSession({ clearStoredControlTokens: true });

    expect(localStorage.getItem('klid-jwt-token')).toBe(session);
    expect(localStorage.getItem('tokenInfo')).not.toBeNull();
  });

  describe('clearControlTokensIfCurrent — 재읽기 가드', () => {
    it('★저장된_access_토큰이_끝내려는_토큰과_다르면_지우지_않는다', () => {
      const stored = makeAccessJwt(1800);
      putControlSession(stored, makeRefreshJwt());

      expect(clearControlTokensIfCurrent(makeAccessJwt(300))).toBe(false);
      expect(localStorage.getItem('klid-jwt-token')).toBe(stored);
      expect(localStorage.getItem('tokenInfo')).not.toBeNull();
    });

    it('같은_토큰이면_지운다', () => {
      const session = makeAccessJwt(300);
      putControlSession(session, makeRefreshJwt());

      expect(clearControlTokensIfCurrent(session)).toBe(true);
      expect(localStorage.getItem('klid-jwt-token')).toBeNull();
      expect(localStorage.getItem('tokenInfo')).toBeNull();
    });

    it('끝내려는_토큰이_없으면_아무것도_하지_않는다', () => {
      const stored = makeAccessJwt(300);
      putControlSession(stored, makeRefreshJwt());

      expect(clearControlTokensIfCurrent(null)).toBe(false);
      expect(localStorage.getItem('klid-jwt-token')).toBe(stored);
    });
  });
});
