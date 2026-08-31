import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { useAuthStore } from '@/stores/useAuthStore';

/**
 * 브라우저 저장소 보관은 **내부(관제) 채널 전용**이라는 사양의 회귀 가드.
 *
 * 포털 채널에서 저작도구는 Host 화면 안의 Remote 이고, access token 은 Host 메모리에만 둔다
 * (`INT-013` — 포털이 제시한 비협상 조건). 우리가 저장소에 사본을 눕히면 ①Host 가 세션을
 * 끝낸 뒤에도 그 사본이 남고 ②Host 가 갱신한 순간 그 사본은 죽은 토큰이 된다.
 *
 * ⚠ 값 축으로 잡는다 — 「setItem 이 안 불렸다」만 보면 키를 바꿔 쓰는 변이를 못 잡으므로
 *   **저장소에 아무 항목도 남지 않는다**를 함께 단언한다.
 */

// helper: base64url encode (UTF-8 safe)
function b64url(obj: Record<string, unknown>): string {
  const json = JSON.stringify(obj);
  const utf8 = unescape(encodeURIComponent(json));
  const b64 = btoa(utf8);
  return b64.replace(/=+$/, '').replace(/\+/g, '-').replace(/\//g, '_');
}

function buildJwt(payload: Record<string, unknown>): string {
  return `${b64url({ alg: 'HS256', typ: 'JWT' })}.${b64url(payload)}.sig`;
}

const VALID_PAYLOAD = {
  sub: 'user-1',
  role: 'PORTAL_USER',
  channel: 'PORTAL',
  exp: 9999999999,
};

/** sessionStorage 에 실제로 남은 항목 전부. 키를 바꿔 쓰는 변이까지 잡는다. */
function sessionEntries(): Record<string, string> {
  const out: Record<string, string> = {};
  for (let i = 0; i < sessionStorage.length; i++) {
    const key = sessionStorage.key(i);
    if (key !== null) out[key] = sessionStorage.getItem(key) ?? '';
  }
  return out;
}

describe('useAuthStore — 채널별 브라우저 저장소 보관', () => {
  beforeEach(() => {
    sessionStorage.clear();
    useAuthStore.setState({ token: null, claims: null });
  });

  afterEach(() => {
    vi.unstubAllEnvs();
    sessionStorage.clear();
  });

  describe('포털 채널 — 저장소에 쓰지 않는다', () => {
    beforeEach(() => {
      vi.stubEnv('VITE_BUILD_CHANNEL', 'portal');
    });

    it('★setToken이_sessionStorage에_아무것도_남기지_않는다', () => {
      useAuthStore.getState().setToken(buildJwt(VALID_PAYLOAD));

      expect(sessionEntries()).toEqual({});
    });

    it('★setTokenAndClaims도_sessionStorage에_아무것도_남기지_않는다', () => {
      useAuthStore.getState().setTokenAndClaims(buildJwt(VALID_PAYLOAD));

      expect(sessionEntries()).toEqual({});
    });

    it('저장소에_쓰지_않아도_메모리_claims는_그대로_채워진다', () => {
      // ⚠ 끄는 것은 「저장소에 눕히는 것」 하나뿐이다. 메모리까지 끄면 화면의 권한 판정이
      //   통째로 빈다(역할·채널이 claims 에서 나온다).
      useAuthStore.getState().setToken(buildJwt(VALID_PAYLOAD));

      const state = useAuthStore.getState();
      expect(state.token).not.toBeNull();
      expect(state.claims?.sub).toBe('user-1');
      expect(state.claims?.channel).toBe('PORTAL');
    });

    it('hydrate가_저장소를_읽지_않는다_앞_채널이_남긴_값을_주워오지_않는다', () => {
      // 같은 브라우저에서 내부 채널을 먼저 쓴 뒤 포털로 들어온 상황을 모사한다.
      sessionStorage.setItem('klid_jwt', buildJwt(VALID_PAYLOAD));

      useAuthStore.getState().hydrate();

      const state = useAuthStore.getState();
      expect(state.token).toBeNull();
      expect(state.claims).toBeNull();
      expect(state.isHydrated).toBe(true);
    });
  });

  describe('내부(관제) 채널 — 지금 동작 그대로 (무변경 가드)', () => {
    beforeEach(() => {
      vi.stubEnv('VITE_BUILD_CHANNEL', 'internal');
    });

    it('setToken이_sessionStorage에_토큰을_보관한다', () => {
      const jwt = buildJwt({ ...VALID_PAYLOAD, channel: 'INTERNAL', role: 'REVIEWER' });

      useAuthStore.getState().setToken(jwt);

      expect(sessionEntries()).toEqual({ klid_jwt: jwt });
    });

    it('clear가_sessionStorage에서_토큰을_지운다', () => {
      const jwt = buildJwt({ ...VALID_PAYLOAD, channel: 'INTERNAL', role: 'REVIEWER' });
      useAuthStore.getState().setToken(jwt);

      useAuthStore.getState().clear();

      expect(sessionEntries()).toEqual({});
    });

    it('hydrate가_sessionStorage에서_복원한다', () => {
      const jwt = buildJwt({ ...VALID_PAYLOAD, channel: 'INTERNAL', role: 'REVIEWER' });
      sessionStorage.setItem('klid_jwt', jwt);

      useAuthStore.getState().hydrate();

      const state = useAuthStore.getState();
      expect(state.token).toBe(jwt);
      expect(state.claims?.channel).toBe('INTERNAL');
    });

    it('채널_미설정이면_내부_채널과_같이_보관한다', () => {
      vi.stubEnv('VITE_BUILD_CHANNEL', '');
      const jwt = buildJwt({ ...VALID_PAYLOAD, channel: 'INTERNAL', role: 'REVIEWER' });

      useAuthStore.getState().setToken(jwt);

      expect(sessionEntries()).toEqual({ klid_jwt: jwt });
    });
  });
});
