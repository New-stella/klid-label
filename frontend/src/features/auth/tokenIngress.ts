/**
 * Token Ingress Resolver — URL 파라미터 / cookie / both 전략
 *
 * 보안:
 * - JWT 형식(3 segments) + 길이 제한 + 문자셋(base64url) 검증
 * - alg=none 거부 (FE는 헤더 alg만 확인 — 서명 검증은 BE 책임)
 * - 토큰은 메모리 (useAuthStore)에만 저장, localStorage 미사용
 */

const MAX_JWT_LEN = 4096;
// base64url 문자셋: A-Za-z0-9-_= (= 패딩 허용)
const JWT_PART_RE = /^[A-Za-z0-9_-]+={0,2}$/;

type IngressStrategy = 'url' | 'cookie' | 'both';

function getStrategy(): IngressStrategy {
  const v = (import.meta.env.VITE_TOKEN_INGRESS as string | undefined) ?? 'both';
  if (v === 'url' || v === 'cookie' || v === 'both') return v;
  return 'both';
}

function isValidJwtFormat(token: string): boolean {
  if (typeof token !== 'string') return false;
  if (token.length === 0 || token.length > MAX_JWT_LEN) return false;
  const parts = token.split('.');
  if (parts.length !== 3) return false;
  if (!parts.every((p) => p.length > 0 && JWT_PART_RE.test(p))) return false;
  return true;
}

function base64UrlDecode(part: string): string | null {
  try {
    const padded = part.replace(/-/g, '+').replace(/_/g, '/');
    const padding = padded.length % 4 === 0 ? '' : '='.repeat(4 - (padded.length % 4));
    return atob(padded + padding);
  } catch {
    return null;
  }
}

/**
 * 보안: JWT 헤더의 alg=none 거부.
 * FE에서 서명 검증은 하지 않지만, 명시적으로 알고리즘 다운그레이드 공격 패턴을 차단한다.
 */
function isAlgAcceptable(token: string): boolean {
  const headerB64 = token.split('.')[0];
  const decoded = base64UrlDecode(headerB64);
  if (!decoded) return false;
  try {
    const header = JSON.parse(decoded) as Record<string, unknown>;
    const alg = typeof header.alg === 'string' ? header.alg.toLowerCase() : '';
    if (!alg || alg === 'none') return false;
    return true;
  } catch {
    return false;
  }
}

function readCookie(name: string): string | null {
  if (typeof document === 'undefined' || !document.cookie) return null;
  const prefix = `${name}=`;
  const segments = document.cookie.split(';');
  for (const seg of segments) {
    const trimmed = seg.trim();
    if (trimmed.startsWith(prefix)) {
      const value = trimmed.substring(prefix.length);
      // cookie 값이 base64url JWT 형식이 아닐 가능성 — caller 검증
      try {
        return decodeURIComponent(value);
      } catch {
        return value;
      }
    }
  }
  return null;
}

export interface ResolveTokenParams {
  urlToken: string | null;
  cookieName: string;
}

/**
 * 전략별 토큰 해상도.
 * - url: urlToken만 사용
 * - cookie: 쿠키 cookieName만 사용
 * - both: urlToken 우선, 없으면 cookie
 *
 * 어떤 경로든 형식·alg 검증 통과한 토큰만 반환. 그 외는 null.
 */
export function resolveToken(params: ResolveTokenParams): string | null {
  const strategy = getStrategy();
  const candidates: (string | null)[] = [];

  if (strategy === 'url' || strategy === 'both') {
    candidates.push(params.urlToken);
  }
  if (strategy === 'cookie' || strategy === 'both') {
    candidates.push(readCookie(params.cookieName));
  }

  for (const c of candidates) {
    if (c && isValidJwtFormat(c) && isAlgAcceptable(c)) {
      return c;
    }
  }
  return null;
}
