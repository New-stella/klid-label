import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  DEV_HOST_TOKEN_STORAGE_KEY,
  applyStandaloneMountRedirect,
  clearDevHostToken,
  devHostGateway,
  installDevHostTokenHandoff,
  isDevStandaloneHostEnabled,
  resolveStandaloneMountRedirect,
  seedDevHostToken,
} from '@/features/auth/devHostStub';
import {
  HOST_HANDOFF_METHOD_NAMES,
  clearHostTokenHandoff,
  getAccessToken,
} from '@/features/auth/tokenHandoff';
import { PORTAL_MOUNT_BASENAME, resolveRouterBasename } from '@/lib/remoteMount';
import { LOCAL_STORAGE_TOKEN_KEY } from '@/features/auth/tokenIngress';
import { useAuthStore } from '@/stores/useAuthStore';

/**
 * Host 없이 단독으로 띄우는 개발 형상의 대역(`INT-013`) 회귀 가드.
 *
 * 지켜야 하는 축은 넷이다 — ①실제 Host 와 **같은 계약**을 쓴다 ②본체의 저장소 불변식을
 * 되살리지 않는다 ③이동 목적지가 산출 시점 상수에서만 나온다 ④관제 채널은 한 글자도 바뀌지
 * 않는다.
 */

function b64url(obj: Record<string, unknown>): string {
  const json = JSON.stringify(obj);
  const utf8 = unescape(encodeURIComponent(json));
  return btoa(utf8).replace(/=+$/, '').replace(/\+/g, '-').replace(/\//g, '_');
}

function buildJwt(payload: Record<string, unknown>): string {
  return `${b64url({ alg: 'HS256', typ: 'JWT' })}.${b64url(payload)}.signature`;
}

const LIVE_PORTAL_JWT = buildJwt({
  sub: '3001',
  role: 'PORTAL_USER',
  channel: 'PORTAL',
  exp: 9999999999,
  name: '홍길동',
});

const EXPIRED_PORTAL_JWT = buildJwt({
  sub: '3001',
  role: 'PORTAL_USER',
  channel: 'PORTAL',
  exp: 1000,
  name: '홍길동',
});

/** sessionStorage 에 실제로 남은 항목 전부 — 키를 바꿔 쓰는 변이까지 잡는다. */
function sessionEntries(): Record<string, string> {
  const out: Record<string, string> = {};
  for (let i = 0; i < sessionStorage.length; i++) {
    const key = sessionStorage.key(i);
    if (key !== null) out[key] = sessionStorage.getItem(key) ?? '';
  }
  return out;
}

describe('devHostStub — Host 대역 (개발 단독 구동 전용)', () => {
  beforeEach(() => {
    sessionStorage.clear();
    localStorage.clear();
    clearHostTokenHandoff();
    clearDevHostToken();
    vi.spyOn(console, 'info').mockImplementation(() => {});
  });

  afterEach(() => {
    vi.unstubAllEnvs();
    vi.restoreAllMocks();
    clearHostTokenHandoff();
    sessionStorage.clear();
    localStorage.clear();
  });

  describe('활성 조건 — 세 축을 모두 만족할 때만이다', () => {
    it('포털_채널_개발_형상에서만_켜진다', () => {
      vi.stubEnv('VITE_BUILD_CHANNEL', 'portal');

      expect(isDevStandaloneHostEnabled()).toBe(true);
    });

    it('★채널_미지정이면_꺼진다_관제향_무영향', () => {
      // 기존 빌드(`VITE_BUILD_CHANNEL` 미설정)에 이 장치가 붙으면 안 된다.
      vi.stubEnv('VITE_BUILD_CHANNEL', '');

      expect(isDevStandaloneHostEnabled()).toBe(false);
    });

    it('★채널이_control이면_꺼진다', () => {
      vi.stubEnv('VITE_BUILD_CHANNEL', 'control');

      expect(isDevStandaloneHostEnabled()).toBe(false);
    });

    it('오타_채널값은_기본값으로_떨어져_꺼진다_failClosed', () => {
      vi.stubEnv('VITE_BUILD_CHANNEL', 'portall');

      expect(isDevStandaloneHostEnabled()).toBe(false);
    });
  });

  describe('계약 — 실제 Host 가 쓰는 것과 같은 네 이름이다', () => {
    /**
     * ★ 따로 판 우회로였다면 실제 Host 계약을 어겨도 개발 단계에서 드러나지 않는다.
     *   대역이 **같은 등록 창구를 통과**하는 것이 이 장치의 값어치다.
     */
    it('대역_창구가_계약_이름_4종을_빠짐없이_구현하고_그_밖의_키를_두지_않는다', () => {
      expect(Object.keys(devHostGateway).sort()).toEqual([...HOST_HANDOFF_METHOD_NAMES].sort());
      for (const name of HOST_HANDOFF_METHOD_NAMES) {
        expect(typeof (devHostGateway as unknown as Record<string, unknown>)[name]).toBe(
          'function',
        );
      }
    });

    it('★등록_창구를_실제로_통과한다_우회로를_만들지_않는다', () => {
      vi.stubEnv('VITE_BUILD_CHANNEL', 'portal');

      expect(installDevHostTokenHandoff()).toBe(true);
      seedDevHostToken(LIVE_PORTAL_JWT);

      // 본체의 조달 지점(`getAccessToken`)이 대역에서 값을 받는다.
      expect(getAccessToken()).toBe(LIVE_PORTAL_JWT);
    });

    it('갱신_요청은_보관분을_그대로_돌려준다_재발급_능력이_없다', async () => {
      vi.stubEnv('VITE_BUILD_CHANNEL', 'portal');
      seedDevHostToken(LIVE_PORTAL_JWT);

      await expect(devHostGateway.refresh()).resolves.toBe(LIVE_PORTAL_JWT);
    });

    it('활동_통지는_아무_일도_하지_않으며_예외를_던지지_않는다', () => {
      expect(() => devHostGateway.notifyActivity()).not.toThrow();
    });
  });

  describe('토큰 보관 — 대역이 자기 몫으로 든다', () => {
    beforeEach(() => {
      vi.stubEnv('VITE_BUILD_CHANNEL', 'portal');
    });

    it('★본체의_인계_키와_보관_키를_재사용하지_않는다', () => {
      seedDevHostToken(LIVE_PORTAL_JWT);

      // 대역의 자리에만 있다 — 저장소를 들여다본 사람이 「본체가 흘린 사본」과 구분할 수 있어야 한다.
      expect(sessionEntries()).toEqual({ [DEV_HOST_TOKEN_STORAGE_KEY]: LIVE_PORTAL_JWT });
      expect(sessionStorage.getItem('klid_jwt')).toBeNull();
      expect(localStorage.getItem(LOCAL_STORAGE_TOKEN_KEY)).toBeNull();
    });

    it('★관제_채널에서는_보관을_거부한다_대역이_살지_않는_형상이다', () => {
      vi.stubEnv('VITE_BUILD_CHANNEL', 'control');

      expect(seedDevHostToken(LIVE_PORTAL_JWT)).toBe(false);
      expect(sessionEntries()).toEqual({});
    });

    it('★새로고침을_모사하면_보관분을_본체에_다시_건넨다_다시_로그인하지_않는다', () => {
      // given: 로그인 직후 상태
      installDevHostTokenHandoff();
      seedDevHostToken(LIVE_PORTAL_JWT);
      useAuthStore.getState().setTokenAndClaims(LIVE_PORTAL_JWT);

      // when: 새로고침 — 메모리(스토어·창구)가 통째로 비고 진입점이 다시 돈다.
      //       포털 채널은 본체가 저장소에서 복원하지 않으므로 남는 것은 대역의 보관분뿐이다.
      useAuthStore.getState().clear();
      clearHostTokenHandoff();
      expect(useAuthStore.getState().claims).toBeNull();

      installDevHostTokenHandoff();

      // then
      expect(getAccessToken()).toBe(LIVE_PORTAL_JWT);
      expect(useAuthStore.getState().claims?.channel).toBe('PORTAL');
      expect(useAuthStore.getState().claims?.role).toBe('PORTAL_USER');
    });

    /**
     * ★ 만료분을 건네면 라우트 가드가 「만료」로 판정해 상위 시스템 로그인으로 보내려 하는데,
     *   단독 구동 환경에는 갈 상위 시스템이 없어 화면이 멈춘다(빈 스피너).
     */
    it('★만료된_보관분은_건네지_않고_버린다', () => {
      sessionStorage.setItem(DEV_HOST_TOKEN_STORAGE_KEY, EXPIRED_PORTAL_JWT);

      installDevHostTokenHandoff();

      expect(getAccessToken()).toBeNull();
      expect(useAuthStore.getState().claims).toBeNull();
      expect(sessionEntries()).toEqual({});
    });

    it('보관분이_없으면_아무것도_건네지_않는다', () => {
      installDevHostTokenHandoff();

      expect(getAccessToken()).toBeNull();
      expect(useAuthStore.getState().claims).toBeNull();
    });

    it('인증_끊김_통지는_보관분과_본체_세션을_함께_비운다', () => {
      const replace = vi.fn();
      vi.spyOn(window, 'location', 'get').mockReturnValue({
        ...window.location,
        replace,
      } as unknown as Location);
      installDevHostTokenHandoff();
      seedDevHostToken(LIVE_PORTAL_JWT);
      useAuthStore.getState().setTokenAndClaims(LIVE_PORTAL_JWT);

      devHostGateway.onUnauthorized();

      expect(getAccessToken()).toBeNull();
      expect(useAuthStore.getState().claims).toBeNull();
      expect(sessionEntries()).toEqual({});
      expect(replace).toHaveBeenCalledWith(`${PORTAL_MOUNT_BASENAME}/dev/login`);
    });
  });

  describe('기준 경로 밖 진입 보정', () => {
    describe('포털 채널', () => {
      beforeEach(() => {
        vi.stubEnv('VITE_BUILD_CHANNEL', 'portal');
      });

      it('★가리키는_화면이_없는_루트_진입은_진입_페이지로_보낸다', () => {
        // 마운트 경로 아래의 `/` 로 보내면 관제 채널 루트 라우트에 걸려 포털 사용자에게
        // 접근 거부 안내가 뜬다. 진입 페이지는 채널을 스스로 판정해 알맞은 자리로 보낸다.
        expect(resolveStandaloneMountRedirect('/')).toBe(`${PORTAL_MOUNT_BASENAME}/ingress`);
      });

      it('그_밖의_주소는_마운트_경로_아래로_그대로_옮긴다', () => {
        expect(resolveStandaloneMountRedirect('/dev/login')).toBe(
          `${PORTAL_MOUNT_BASENAME}/dev/login`,
        );
        expect(resolveStandaloneMountRedirect('/portal/uploads')).toBe(
          `${PORTAL_MOUNT_BASENAME}/portal/uploads`,
        );
      });

      it('이미_마운트_경로_아래면_옮기지_않는다_되풀이_이동이_생기지_않는다', () => {
        expect(resolveStandaloneMountRedirect(PORTAL_MOUNT_BASENAME)).toBeNull();
        expect(resolveStandaloneMountRedirect(`${PORTAL_MOUNT_BASENAME}/portal`)).toBeNull();
        expect(resolveStandaloneMountRedirect(`${PORTAL_MOUNT_BASENAME}/`)).toBeNull();
      });

      /**
       * ★★ 이 장치에서 가장 위험한 지점이다 — 기준 경로가 곧 이동 표면이 되면 바깥 주소로
       *    끌려간다. 목적지의 밑동은 **언제나 산출 시점에 굳은 상수**여야 한다.
       */
      it('★출처를_바꾸는_앞머리가_와도_같은_출처_하위_경로로만_만들어진다', () => {
        const hostile = [
          '//evil.example/steal',
          '///evil.example',
          '/\\evil.example',
          '\\\\evil.example',
        ];

        for (const pathname of hostile) {
          const target = resolveStandaloneMountRedirect(pathname);
          expect(target).not.toBeNull();
          const value = target as string;
          // 밑동이 상수다 — 프로토콜 상대(`//`)로도, 절대 URL 로도 시작하지 않는다.
          expect(value.startsWith(`${PORTAL_MOUNT_BASENAME}/`)).toBe(true);
          expect(value.startsWith('//')).toBe(false);
          expect(value).not.toMatch(/^[a-zA-Z][a-zA-Z0-9+.-]*:/);
        }
      });

      it('실행_중_입력인_질의문자열과_해시는_목적지에_섞이지_않는다', () => {
        // 목적지 조립에 쓰는 것은 현재 문서의 **경로**뿐이다.
        const replace = vi.fn();
        vi.spyOn(window, 'location', 'get').mockReturnValue({
          pathname: '/dev/login',
          search: '?token=leaked',
          hash: '#frag',
          replace,
        } as unknown as Location);

        expect(applyStandaloneMountRedirect()).toBe(true);
        expect(replace).toHaveBeenCalledWith(`${PORTAL_MOUNT_BASENAME}/dev/login`);
        expect(replace.mock.calls[0][0]).not.toContain('token=');
        expect(replace.mock.calls[0][0]).not.toContain('#');
      });
    });

    describe('관제 채널 — 보정이 동작하지 않는다', () => {
      it('★채널_미지정이면_라우터_기준_경로도_보정도_없다', () => {
        vi.stubEnv('VITE_BUILD_CHANNEL', '');

        // 두 축을 짝으로 단언한다 — 기준 경로가 없는데 보정만 살아 있으면 관제향 주소가
        // 존재하지 않는 하위 경로로 밀려 전 화면이 사라진다.
        expect(resolveRouterBasename()).toBeUndefined();
        expect(resolveStandaloneMountRedirect('/')).toBeNull();
        expect(resolveStandaloneMountRedirect('/dashboard')).toBeNull();
      });

      it('★채널이_control이어도_같다', () => {
        vi.stubEnv('VITE_BUILD_CHANNEL', 'control');

        expect(resolveRouterBasename()).toBeUndefined();
        expect(resolveStandaloneMountRedirect('/dashboard')).toBeNull();
      });

      it('보정이_없으면_이동도_시도하지_않는다', () => {
        vi.stubEnv('VITE_BUILD_CHANNEL', 'control');
        const replace = vi.fn();
        vi.spyOn(window, 'location', 'get').mockReturnValue({
          pathname: '/dashboard',
          replace,
        } as unknown as Location);

        expect(applyStandaloneMountRedirect()).toBe(false);
        expect(replace).not.toHaveBeenCalled();
      });
    });
  });
});
