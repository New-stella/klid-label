import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { resolveToken } from '../tokenIngress';

const LS_KEY = 'klid-jwt-token';

// helper: base64url
function b64url(obj: Record<string, unknown>): string {
  const json = JSON.stringify(obj);
  const utf8 = unescape(encodeURIComponent(json));
  const b64 = btoa(utf8);
  return b64.replace(/=+$/, '').replace(/\+/g, '-').replace(/\//g, '_');
}

function buildJwt(headerObj: Record<string, unknown>, payloadObj: Record<string, unknown>): string {
  return `${b64url(headerObj)}.${b64url(payloadObj)}.signature`;
}

describe('resolveToken (URL/cookie/localStorage 분기 + 보안 검증)', () => {
  beforeEach(() => {
    try {
      localStorage.clear();
    } catch {
      // ignore (test env edge case)
    }
  });

  afterEach(() => {
    vi.unstubAllEnvs();
    // cookie clear
    document.cookie.split(';').forEach((c) => {
      const eq = c.indexOf('=');
      const name = (eq > -1 ? c.substr(0, eq) : c).trim();
      document.cookie = `${name}=;expires=Thu, 01 Jan 1970 00:00:00 GMT;path=/`;
    });
    try {
      localStorage.clear();
    } catch {
      // ignore
    }
  });

  it('URL_파라미터_token_수령_후_useAuthStore에_저장', () => {
    vi.stubEnv('VITE_TOKEN_INGRESS', 'url');
    const tok = buildJwt({ alg: 'HS256', typ: 'JWT' }, { sub: 'u1', exp: 9999999999 });

    const result = resolveToken({ urlToken: tok, cookieName: 'klid_jwt' });
    expect(result).toBe(tok);
  });

  it('cookie_klid_jwt_수령_경로_분기', () => {
    vi.stubEnv('VITE_TOKEN_INGRESS', 'cookie');
    const tok = buildJwt({ alg: 'HS256', typ: 'JWT' }, { sub: 'u1', exp: 9999999999 });
    document.cookie = `klid_jwt=${tok};path=/`;

    const result = resolveToken({ urlToken: null, cookieName: 'klid_jwt' });
    expect(result).toBe(tok);
  });

  it('both_전략은_URL_우선_없으면_cookie_사용', () => {
    vi.stubEnv('VITE_TOKEN_INGRESS', 'both');
    const cookieTok = buildJwt({ alg: 'HS256', typ: 'JWT' }, { sub: 'cookie', exp: 9999999999 });
    document.cookie = `klid_jwt=${cookieTok};path=/`;

    const urlTok = buildJwt({ alg: 'HS256', typ: 'JWT' }, { sub: 'url', exp: 9999999999 });
    expect(resolveToken({ urlToken: urlTok, cookieName: 'klid_jwt' })).toBe(urlTok);

    expect(resolveToken({ urlToken: null, cookieName: 'klid_jwt' })).toBe(cookieTok);
  });

  it('JWT_형식이_아닌_입력은_null_반환', () => {
    vi.stubEnv('VITE_TOKEN_INGRESS', 'url');
    expect(resolveToken({ urlToken: 'not-a-jwt', cookieName: 'klid_jwt' })).toBeNull();
    expect(resolveToken({ urlToken: 'a.b', cookieName: 'klid_jwt' })).toBeNull();
  });

  it('JWT_alg_none은_거부', () => {
    vi.stubEnv('VITE_TOKEN_INGRESS', 'url');
    const tok = buildJwt({ alg: 'none', typ: 'JWT' }, { sub: 'u1', exp: 9999999999 });
    expect(resolveToken({ urlToken: tok, cookieName: 'klid_jwt' })).toBeNull();
  });

  it('cookie에_허용_문자_외_값_있으면_null', () => {
    vi.stubEnv('VITE_TOKEN_INGRESS', 'cookie');
    document.cookie = 'klid_jwt=invalid value with spaces;path=/';
    // spaces are allowed in raw cookie strings but cookie value parser should reject
    // we set explicitly an invalid format
    expect(resolveToken({ urlToken: null, cookieName: 'klid_jwt' })).toBeNull();
  });

  // --- localStorage 인계 채널 (관제서버 표준 키 'klid-jwt-token') ---

  it('localStorage에_유효_JWT_있으면_반환', () => {
    vi.stubEnv('VITE_TOKEN_INGRESS', 'all');
    const tok = buildJwt({ alg: 'HS256', typ: 'JWT' }, { sub: 'ls', exp: 9999999999 });
    localStorage.setItem(LS_KEY, tok);

    expect(resolveToken({ urlToken: null, cookieName: 'klid_jwt' })).toBe(tok);
  });

  it('URL_토큰이_있으면_localStorage_무시', () => {
    vi.stubEnv('VITE_TOKEN_INGRESS', 'all');
    const lsTok = buildJwt({ alg: 'HS256', typ: 'JWT' }, { sub: 'ls', exp: 9999999999 });
    const urlTok = buildJwt({ alg: 'HS256', typ: 'JWT' }, { sub: 'url', exp: 9999999999 });
    localStorage.setItem(LS_KEY, lsTok);

    expect(resolveToken({ urlToken: urlTok, cookieName: 'klid_jwt' })).toBe(urlTok);
  });

  it('localStorage_값이_잘못된_형식이면_null', () => {
    vi.stubEnv('VITE_TOKEN_INGRESS', 'localStorage');
    localStorage.setItem(LS_KEY, 'not-a-jwt');
    expect(resolveToken({ urlToken: null, cookieName: 'klid_jwt' })).toBeNull();

    localStorage.setItem(LS_KEY, 'only.two');
    expect(resolveToken({ urlToken: null, cookieName: 'klid_jwt' })).toBeNull();
  });

  it('localStorage_값이_alg_none_이면_거부', () => {
    vi.stubEnv('VITE_TOKEN_INGRESS', 'localStorage');
    const tok = buildJwt({ alg: 'none', typ: 'JWT' }, { sub: 'u1', exp: 9999999999 });
    localStorage.setItem(LS_KEY, tok);
    expect(resolveToken({ urlToken: null, cookieName: 'klid_jwt' })).toBeNull();
  });

  it('VITE_TOKEN_INGRESS_localStorage_전략이면_url_쿠키_무시', () => {
    vi.stubEnv('VITE_TOKEN_INGRESS', 'localStorage');
    const lsTok = buildJwt({ alg: 'HS256', typ: 'JWT' }, { sub: 'ls', exp: 9999999999 });
    const urlTok = buildJwt({ alg: 'HS256', typ: 'JWT' }, { sub: 'url', exp: 9999999999 });
    const cookieTok = buildJwt({ alg: 'HS256', typ: 'JWT' }, { sub: 'cookie', exp: 9999999999 });
    localStorage.setItem(LS_KEY, lsTok);
    document.cookie = `klid_jwt=${cookieTok};path=/`;

    expect(resolveToken({ urlToken: urlTok, cookieName: 'klid_jwt' })).toBe(lsTok);
  });

  it('VITE_TOKEN_INGRESS_미설정이면_url_localStorage_cookie_순_시도', () => {
    // env 미설정 → 기본 전략 'all' (url > localStorage > cookie)
    const lsTok = buildJwt({ alg: 'HS256', typ: 'JWT' }, { sub: 'ls', exp: 9999999999 });
    const cookieTok = buildJwt({ alg: 'HS256', typ: 'JWT' }, { sub: 'cookie', exp: 9999999999 });
    const urlTok = buildJwt({ alg: 'HS256', typ: 'JWT' }, { sub: 'url', exp: 9999999999 });
    document.cookie = `klid_jwt=${cookieTok};path=/`;

    // url 있으면 url
    expect(resolveToken({ urlToken: urlTok, cookieName: 'klid_jwt' })).toBe(urlTok);

    // url 없고 localStorage 있으면 localStorage
    localStorage.setItem(LS_KEY, lsTok);
    expect(resolveToken({ urlToken: null, cookieName: 'klid_jwt' })).toBe(lsTok);

    // url/localStorage 없으면 cookie
    localStorage.removeItem(LS_KEY);
    expect(resolveToken({ urlToken: null, cookieName: 'klid_jwt' })).toBe(cookieTok);
  });

  it('VITE_TOKEN_INGRESS_잘못된_값이면_all로_폴백', () => {
    vi.stubEnv('VITE_TOKEN_INGRESS', 'invalid-strategy');
    const lsTok = buildJwt({ alg: 'HS256', typ: 'JWT' }, { sub: 'ls', exp: 9999999999 });
    localStorage.setItem(LS_KEY, lsTok);
    expect(resolveToken({ urlToken: null, cookieName: 'klid_jwt' })).toBe(lsTok);
  });
});
