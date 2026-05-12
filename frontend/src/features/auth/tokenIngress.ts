/**
 * Token Ingress Resolver — URL 파라미터 / localStorage / cookie 인계 전략
 *
 * 보안:
 * - JWT 형식(3 segments) + 길이 제한 + 문자셋(base64url) 검증
 * - alg=none 거부 (FE는 헤더 alg만 확인 — 서명 검증은 BE 책임)
 * - 토큰은 메모리 + sessionStorage (useAuthStore) 에 저장.
 *   localStorage 는 관제서버 인계 채널로 **읽기만** — 저작도구가 새로 쓰지 않는다.
 *   (관제서버가 같은 origin 의 localStorage[`klid-jwt-token`] 에 JWT 를 두면 저작도구가
 *    그것을 인계받아 sessionStorage 로 이전한다 — XSS 표면 확대 없음.)
 */

const MAX_JWT_LEN = 4096;
// base64url 문자셋: A-Za-z0-9-_= (= 패딩 허용)
const JWT_PART_RE = /^[A-Za-z0-9_-]+={0,2}$/;

/** 관제서버 인계 표준 키 (변경 금지 — 양측 합의) */
const LOCAL_STORAGE_TOKEN_KEY = 'klid-jwt-token';

type IngressStrategy = 'url' | 'cookie' | 'localStorage' | 'both' | 'all';

function getStrategy(): IngressStrategy {
  const v = (import.meta.env.VITE_TOKEN_INGRESS as string | undefined) ?? 'all';
  if (
    v === 'url' ||
    v === 'cookie' ||
    v === 'localStorage' ||
    v === 'both' ||
    v === 'all'
  ) {
    return v;
  }
  return 'all';
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

/**
 * 관제서버 인계 채널: localStorage[`klid-jwt-token`] 읽기 전용.
 * - SSR / 비브라우저 환경 가드
 * - private mode / quota / disabled storage 등 예외 안전
 * - 값 자체는 형식 검증 없이 반환 — 호출부에서 통합 검증 (isValidJwtFormat + isAlgAcceptable)
 */
function readLocalStorageToken(): string | null {
  if (typeof localStorage === 'undefined') return null;
  try {
    return localStorage.getItem(LOCAL_STORAGE_TOKEN_KEY);
  } catch {
    return null;
  }
}

export interface ResolveTokenParams {
  urlToken: string | null;
  cookieName: string;
}

/**
 * 전략별 토큰 해상도.
 * - url: urlToken만 사용
 * - cookie: 쿠키 cookieName만 사용
 * - localStorage: 관제서버 인계 키만 사용
 * - both: urlToken → cookie (레거시 호환)
 * - all (기본값): urlToken → localStorage → cookie
 *
 * 어떤 경로든 형식·alg 검증 통과한 첫 토큰만 반환. 그 외는 null.
 */
export function resolveToken(params: ResolveTokenParams): string | null {
  const strategy = getStrategy();
  const candidates: (string | null)[] = [];

  // 우선순위:
  //   1) URL (?token=...)      — 명시적 인계
  //   2) localStorage (klid-jwt-token) — 관제서버 인계
  //   3) cookie (klid_jwt)     — 레거시/대체
  if (strategy === 'url' || strategy === 'both' || strategy === 'all') {
    candidates.push(params.urlToken);
  }
  if (strategy === 'localStorage' || strategy === 'all') {
    candidates.push(readLocalStorageToken());
  }
  if (strategy === 'cookie' || strategy === 'both' || strategy === 'all') {
    candidates.push(readCookie(params.cookieName));
  }

  for (const c of candidates) {
    if (c && isValidJwtFormat(c) && isAlgAcceptable(c)) {
      return c;
    }
  }
  return null;
}
