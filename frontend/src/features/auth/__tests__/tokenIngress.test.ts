import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { resolveHandoffUser, resolveToken } from '../tokenIngress';

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

  it('VITE_TOKEN_INGRESS_미설정이면_localStorage_채널만_쓴다', () => {
    // 구 동작: 미설정 → 'all' (url > localStorage > cookie).
    // 확정 정책(ADR-012)이 `?token=` 쿼리 방식을 쓰지 않으므로 기본값을 'localStorage' 로 좁혔다.
    // 명시 설정('url'/'both'/'all')의 동작은 위 케이스들이 그대로 보장한다 — 바뀐 것은 기본값뿐이다.
    const lsTok = buildJwt({ alg: 'HS256', typ: 'JWT' }, { sub: 'ls', exp: 9999999999 });
    const cookieTok = buildJwt({ alg: 'HS256', typ: 'JWT' }, { sub: 'cookie', exp: 9999999999 });
    const urlTok = buildJwt({ alg: 'HS256', typ: 'JWT' }, { sub: 'url', exp: 9999999999 });
    document.cookie = `klid_jwt=${cookieTok};path=/`;

    // url·cookie 만 있으면 채택할 토큰이 없다
    expect(resolveToken({ urlToken: urlTok, cookieName: 'klid_jwt' })).toBeNull();

    // localStorage 인계 채널만 채택한다 (url 토큰이 함께 있어도)
    localStorage.setItem(LS_KEY, lsTok);
    expect(resolveToken({ urlToken: urlTok, cookieName: 'klid_jwt' })).toBe(lsTok);
    expect(resolveToken({ urlToken: null, cookieName: 'klid_jwt' })).toBe(lsTok);
  });

  it('★VITE_TOKEN_INGRESS_미설정이면_URL_쿼리_토큰을_채택하지_않는다', () => {
    // 회귀 가드 — URL 에 실린 JWT 는 접근 로그·리퍼러 헤더·브라우저 히스토리에 잔존해
    // 사후 회수가 불가능하다(CWE-598/200). 기본값이 URL 채널을 열면 안 된다.
    const urlTok = buildJwt({ alg: 'HS256', typ: 'JWT' }, { sub: 'url', exp: 9999999999 });

    const result = resolveToken({ urlToken: urlTok, cookieName: 'klid_jwt' });

    expect(result).toBeNull();
    expect(result).not.toBe(urlTok);
  });

  it('VITE_TOKEN_INGRESS_잘못된_값이면_기본값_localStorage_로_폴백', () => {
    // 오타·미지의 값은 fail-closed 로 안전한 기본값에 떨어진다 (구 동작: 'all' 폴백).
    vi.stubEnv('VITE_TOKEN_INGRESS', 'invalid-strategy');
    const lsTok = buildJwt({ alg: 'HS256', typ: 'JWT' }, { sub: 'ls', exp: 9999999999 });
    const urlTok = buildJwt({ alg: 'HS256', typ: 'JWT' }, { sub: 'url', exp: 9999999999 });
    localStorage.setItem(LS_KEY, lsTok);

    expect(resolveToken({ urlToken: urlTok, cookieName: 'klid_jwt' })).toBe(lsTok);

    localStorage.removeItem(LS_KEY);
    expect(resolveToken({ urlToken: urlTok, cookieName: 'klid_jwt' })).toBeNull();
  });
});

describe('resolveHandoffUser (관제 인계 표시 정보)', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  afterEach(() => {
    localStorage.clear();
  });

  it('관제가_넣은_userId_userNm_을_읽는다', () => {
    localStorage.setItem('userId', 'sjs123');
    localStorage.setItem('userNm', '신재석');

    expect(resolveHandoffUser()).toEqual({ userId: 'sjs123', userNm: '신재석' });
  });

  it('인계값이_없으면_빈_객체다_하위호환', () => {
    // 구 관제(표시 정보를 안 넣는 버전)에서도 클레임 요청은 성립해야 한다 — BE 선택 필드.
    expect(resolveHandoffUser()).toEqual({});
  });

  it('공백_전용_값은_생략된다', () => {
    localStorage.setItem('userId', '   ');
    localStorage.setItem('userNm', '\t\n');

    expect(resolveHandoffUser()).toEqual({});
  });

  it('컬럼_길이를_넘는_값은_자르지_않고_생략된다', () => {
    // 잘라 보내면 <잘린 이름>이 사람의 진짜 이름인 양 저장된다. 미전달이면 기존 값이 보존된다.
    localStorage.setItem('userId', 'a'.repeat(21));
    localStorage.setItem('userNm', '가'.repeat(101));

    expect(resolveHandoffUser()).toEqual({});
  });

  it('authority_는_읽지_않는다_권한판정_단일진실원은_서버다', () => {
    // CWE-807 — 브라우저 저장소 문자열로 권한을 판정하면 사용자가 값을 바꿔 권한을 올린다.
    localStorage.setItem('authority', 'PLTF_MANAGER');
    localStorage.setItem('userId', 'sjs123');

    const handoff = resolveHandoffUser();

    expect(handoff).toEqual({ userId: 'sjs123' });
    expect(Object.keys(handoff)).not.toContain('authority');
  });
});
