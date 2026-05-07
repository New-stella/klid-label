import { afterEach, describe, expect, it, vi } from 'vitest';

import { resolveToken } from '../tokenIngress';

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

describe('resolveToken (URL/cookie 분기 + 보안 검증)', () => {
  afterEach(() => {
    vi.unstubAllEnvs();
    // cookie clear
    document.cookie.split(';').forEach((c) => {
      const eq = c.indexOf('=');
      const name = (eq > -1 ? c.substr(0, eq) : c).trim();
      document.cookie = `${name}=;expires=Thu, 01 Jan 1970 00:00:00 GMT;path=/`;
    });
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
});
