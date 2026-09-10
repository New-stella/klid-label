/**
 * 관제 채널 세션 연장 시험 공용 픽스처.
 *
 * 관제 access 토큰의 실측 클레임 구성(HS512 · `sub="admin"` · `iss`·`channel`·`role` 없음 ·
 * `sessionExpAlarm`·`sessionTime`·`inaccessible_menus` 등)을 흉내 낸다 — 서명은 검증하지 않으므로
 * 가짜 서명 조각을 붙인다.
 */
import { vi } from 'vitest';

import { Channel, Role } from '@/lib/api/types';
import { useAuthStore } from '@/stores/useAuthStore';

function base64UrlUtf8(text: string): string {
  const bytes = new TextEncoder().encode(text);
  let binary = '';
  bytes.forEach((b) => {
    binary += String.fromCharCode(b);
  });
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

export function makeJwt(claims: Record<string, unknown>): string {
  return `${base64UrlUtf8(JSON.stringify({ alg: 'HS512' }))}.${base64UrlUtf8(
    JSON.stringify(claims),
  )}.c2ln`;
}

export function nowSec(): number {
  return Math.floor(Date.now() / 1000);
}

let accessSeq = 0;

/**
 * 관제 access 토큰 클레임 — `lifetimeSec` 뒤 만료.
 *
 * ⚠ `jti` 로 매번 다른 토큰을 만든다 — 같은 초에 같은 수명으로 만든 두 토큰은 **글자까지 같아져**,
 *   앞 시험이 남긴 `tokenInfo` 가 뒤 시험의 토큰과 「짝」으로 판정된다(실측으로 겪었다).
 */
export function accessClaims(
  lifetimeSec: number,
  overrides: Record<string, unknown> = {},
): Record<string, unknown> {
  const iat = nowSec();
  accessSeq += 1;
  return {
    jti: `A-${accessSeq}`,
    sub: 'admin',
    userId: 'admin',
    userNm: '시스템관리자',
    authority: 'PLTF_MANAGER',
    sessionId: 'S-1',
    type: 'access',
    sessionExpAlarm: 5,
    sessionTime: 30,
    eventDate: 7,
    rawVideoDate: 30,
    inaccessible_menus: ['M01'],
    userOgCd: 'OG1',
    userSido: '11',
    userSgg: '110',
    iat,
    exp: iat + lifetimeSec,
    ...overrides,
  };
}

export function makeAccessJwt(lifetimeSec: number, overrides: Record<string, unknown> = {}) {
  return makeJwt(accessClaims(lifetimeSec, overrides));
}

let refreshSeq = 0;
/** 관제 refresh 토큰 — 매번 다른 값. */
export function makeRefreshJwt(): string {
  refreshSeq += 1;
  return makeJwt({ sub: 'admin', type: 'refresh', sessionId: 'S-1', seq: refreshSeq });
}

/**
 * 관제 `saveTokens(session, refresh)` 의 **기준 재현** — 제품 코드와 독립으로 적는다.
 * 제품 코드의 필드명·순서·단위가 어긋나면 이것과의 문자열 비교가 실패한다.
 *
 * ★ `refresh` 를 넘기지 않으면 관제 `saveTokens(session, undefined)` 의 재현이다 —
 *   `JSON.stringify` 가 `refresh_token` **키 자체를 뺀다**(빈 문자열이 아니다).
 *   관제가 새 refresh 토큰을 주지 않은 갱신([[API-247]] 201 + `refreshToken: null`)의 저장 형식.
 */
export function referenceControlTokenInfoJson(session: string, refresh?: string): string {
  const payload = JSON.parse(
    new TextDecoder().decode(
      Uint8Array.from(
        atob(session.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')),
        (c) => c.charCodeAt(0),
      ),
    ),
  ) as Record<string, unknown> & { exp: number; iat: number };
  return JSON.stringify({
    access_token: session,
    refresh_token: refresh,
    authority: payload.authority,
    userSido: payload.userSido,
    userSgg: payload.userSgg,
    userOgCd: payload.userOgCd,
    expiresAt: payload.exp * 1000,
    issuedAt: payload.iat * 1000,
    sessionExpAlarm: payload.sessionExpAlarm,
    sessionTime: payload.sessionTime,
    eventDate: payload.eventDate,
    rawVideoDate: payload.rawVideoDate,
    inAccessibleMenus: payload.inaccessible_menus || [],
  });
}

/** 관제 탭이 로그인·갱신한 상태를 저장소에 만든다(관제 `saveTokens` 결과와 같게). */
export function putControlSession(session: string, refresh: string): void {
  localStorage.setItem('klid-jwt-token', session);
  localStorage.setItem('tokenInfo', referenceControlTokenInfoJson(session, refresh));
}

/**
 * 이 탭이 이미 로그인해 서버 역할까지 확보한 상태 — `setToken` 을 쓰지 않는다.
 * (그러면 확보 절차가 `/v1/me` 를 쏘아 갱신 호출 수 단언을 흐린다.)
 */
export function signInTab(session: string): void {
  const payload = JSON.parse(
    new TextDecoder().decode(
      Uint8Array.from(
        atob(session.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')),
        (c) => c.charCodeAt(0),
      ),
    ),
  ) as { sub: string; exp: number };
  useAuthStore.setState({
    token: session,
    claims: {
      sub: payload.sub,
      role: Role.ADMIN,
      channel: Channel.INTERNAL,
      exp: payload.exp,
      name: '홍길동',
    },
    serverRoleStatus: 'ready',
  });
}

/** 상위 로그인 이동을 가로챈다. 되돌리기 함수를 돌려준다. */
export function stubUpstreamRedirect(pathname = '/label/1') {
  const assign = vi.fn();
  const original = window.location;
  Object.defineProperty(window, 'location', {
    writable: true,
    configurable: true,
    value: { ...original, pathname, href: `http://app.local${pathname}`, assign },
  });
  vi.stubEnv('VITE_CONTROL_LOGIN_URL', 'http://control.local/login');
  return {
    assign,
    restore: () => {
      Object.defineProperty(window, 'location', {
        writable: true,
        configurable: true,
        value: original,
      });
    },
  };
}

/** 다른 탭이 저장소를 바꿨을 때 이 탭이 받는 이벤트. */
export function fireStorage(key: string | null, oldValue: string | null, newValue: string | null) {
  window.dispatchEvent(new StorageEvent('storage', { key, oldValue, newValue }));
}
