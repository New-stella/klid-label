/**
 * Token Ingress Resolver — URL 파라미터 / localStorage / cookie 인계 전략
 *
 * 보안:
 * - JWT 형식(3 segments) + 길이 제한 + 문자셋(base64url) 검증
 * - alg=none 거부 (FE는 헤더 alg만 확인 — 서명 검증은 BE 책임)
 * - 토큰은 메모리 + sessionStorage (useAuthStore) 에 저장.
 *   ⚠ **폐기(2026-09-10)** — *"localStorage 는 관제서버 인계 채널로 **읽기만** — 저작도구가 새로
 *     쓰지 않는다 … 인계받아 sessionStorage 로 이전한다"*. 관제 채널은 이제 **세션을 연장**한다:
 *     매 요청 `klid-jwt-token` 의 **현재값**을 읽고(진입 시 복사본이 아니다), 갱신 중계
 *     (`POST /v1/auth/control-tokens`)로 받은 새 토큰 쌍을 **관제와 같은 키·형식**
 *     (`klid-jwt-token` · `tokenInfo` · `userNm`)으로 **쓴다**. 쓰기·갱신은
 *     `features/auth/controlSession` 한 곳이 소유한다. 포털 채널은 무변경(저장소를 쓰지 않는다).
 *     [@design ADR-012] [@design INT-013] [@design API-247] [@design API-246]
 *   ⚠ refresh 토큰(7일)이 같은 출처 저장소에 놓이는 XSS 노출은 인지·수용한 위험이다([@design NFR-013]).
 */

import { resolveConfig } from '@/lib/runtimeConfig';

const MAX_JWT_LEN = 4096;
// base64url 문자셋: A-Za-z0-9-_= (= 패딩 허용)
const JWT_PART_RE = /^[A-Za-z0-9_-]+={0,2}$/;

/** 관제서버 인계 표준 키 (변경 금지 — 양측 합의) */
export const LOCAL_STORAGE_TOKEN_KEY = 'klid-jwt-token';

/**
 * 관제서버가 같은 origin 의 localStorage 에 함께 넣어 주는 <표시용> 사용자 정보 키.
 * 저작도구는 이 값을 역할 클레임 요청에 동봉하고, BE 가 사용자 마스터(LS_ACNT_USER)에 자동등록한다.
 */
export const LOCAL_STORAGE_USER_ID_KEY = 'userId';
export const LOCAL_STORAGE_USER_NM_KEY = 'userNm';

/**
 * 관제서버가 함께 넣어 주는 `authority`(예: `PLTF_MANAGER`) 는 <의도적으로 읽지 않는다>.
 *
 * 저작도구 인가 역할의 단일 진실원은 서버측 `LS_USER_ROLE` 이며, 브라우저 저장소의 문자열을
 * 권한 판정에 쓰면 사용자가 값을 바꿔 권한을 올릴 수 있다(CWE-807 신뢰 불가 입력 기반 판정).
 * 표시 목적으로도 쓰지 않는다 — 값이 서버 판정과 어긋나면 화면이 거짓을 말한다.
 */

/** 표시용 값의 최대 길이 — BE 컬럼(USER_ID 20 / USER_NM 100)과 동일. 초과 시 <보내지 않는다>. */
const MAX_USER_ID_LEN = 20;
const MAX_USER_NM_LEN = 100;

export interface HandoffUser {
  userId?: string;
  userNm?: string;
}

function readLocalStorageItem(key: string): string | null {
  if (typeof localStorage === 'undefined') return null;
  try {
    return localStorage.getItem(key);
  } catch {
    return null;
  }
}

/**
 * 관제 인계 표시 정보를 읽는다.
 *
 * ⚠ **폐기(2026-09-10)** — 괄호 속 *"읽기 전용 — 저작도구는 이 키에 쓰지 않는다"*. 관제 채널의 세션
 *   갱신이 관제 `saveTokens` 와 같은 형식으로 `userNm` 을 **쓴다**(`features/auth/controlSession`).
 *   이 함수 자체는 여전히 읽기만 한다.
 *
 * - 공백 전용/빈 값은 <생략>한다. 빈 문자열을 보내면 BE 에서도 미전달로 정규화되지만,
 *   애초에 보내지 않는 편이 요청 의미가 분명하다.
 * - 길이 초과 값은 <생략>한다. 잘라 보내면 잘린 이름이 사람의 진짜 이름인 양 저장된다.
 *   BE 는 선택 필드라 미전달을 정상 처리하며 기존 값을 보존한다.
 */
export function resolveHandoffUser(): HandoffUser {
  const result: HandoffUser = {};
  const userId = readLocalStorageItem(LOCAL_STORAGE_USER_ID_KEY)?.trim();
  const userNm = readLocalStorageItem(LOCAL_STORAGE_USER_NM_KEY)?.trim();
  if (userId && userId.length <= MAX_USER_ID_LEN) result.userId = userId;
  if (userNm && userNm.length <= MAX_USER_NM_LEN) result.userNm = userNm;
  return result;
}

/**
 * `VITE_TOKEN_INGRESS` 가 해석하는 값의 <단일 진실원>.
 * `vite-env.d.ts` 는 이 목록을 참조만 하고 사본을 두지 않는다 — 사본을 두면 두 번째 진실원이 되어
 * 한쪽만 갱신될 때 타입과 실동작이 조용히 어긋난다.
 */
export const TOKEN_INGRESS_STRATEGIES = [
  'url',
  'cookie',
  'localStorage',
  'both',
  'all',
] as const;

export type IngressStrategy = (typeof TOKEN_INGRESS_STRATEGIES)[number];

/**
 * 기본 전략 — <URL 쿼리 채널을 켜지 않는다>.
 *
 * 확정 정책(ADR-012)상 관제서버/포털은 <동일 origin 의 브라우저 저장소>로 JWT 를 인계하며
 * `?token=` 쿼리 파라미터 방식은 사용하지 않는다. URL 에 실린 JWT 는 웹서버 접근 로그·리퍼러
 * 헤더·브라우저 히스토리에 잔존해 사후 회수가 불가능하다 (CWE-598 / CWE-200).
 *
 * 명시 설정(`url`/`both`/`all`)의 동작은 종전과 같다 — 바뀐 것은 <미설정 시의 기본값>뿐이다.
 */
export const DEFAULT_INGRESS_STRATEGY: IngressStrategy = 'localStorage';

function getStrategy(): IngressStrategy {
  // 런타임 설정 우선(`lib/runtimeConfig`) — 채널마다 인계 수단이 갈리므로 산출물 하나로
  // 두 현장을 덮을 수 있어야 한다. 미설정·오타는 종전대로 안전한 기본값으로 떨어진다.
  const v = resolveConfig('VITE_TOKEN_INGRESS');
  if (v && (TOKEN_INGRESS_STRATEGIES as readonly string[]).includes(v)) {
    return v as IngressStrategy;
  }
  // 미설정·오타·미지의 값은 모두 안전한 기본값으로 떨어진다 (fail-closed).
  return DEFAULT_INGRESS_STRATEGY;
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
 * 관제서버 인계 채널: localStorage[`klid-jwt-token`] 읽기.
 * ⚠ **폐기(2026-09-10)** — 구 표기 *"읽기 전용"*. 이 함수는 읽기만 하지만 그 키 자체는 관제 채널
 *   세션 갱신이 **쓴다**(`features/auth/controlSession.saveControlTokens`).
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
 * - localStorage (기본값): 관제서버 인계 키만 사용
 * - both: urlToken → cookie (레거시 호환)
 * - all: urlToken → localStorage → cookie
 *
 * 미설정 시 기본값은 `localStorage` 다 — URL 쿼리 채널은 <명시 설정한 경우에만> 열린다
 * (`DEFAULT_INGRESS_STRATEGY` 주석 참조).
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

// [@design ADR-012] [@design INT-013]
/**
 * 이 배포가 관제 인계를 **같은 출처 저장소**로 받는가 — 전략 판정을 재사용하는 단일 지점.
 *
 * 관제 채널의 「매 요청 저장소 현재값 읽기」는 저장소가 인계 채널일 때만 성립한다. 인계를 URL·쿠키로만
 * 받도록 설정한 배포는 종전처럼 스토어 값을 쓴다. 전략 판정을 호출부에서 다시 만들지 말 것 —
 * 두 벌이 되면 한쪽만 갱신돼 조용히 갈린다.
 */
export function isLocalStorageIngressEnabled(): boolean {
  const strategy = getStrategy();
  return strategy === 'localStorage' || strategy === 'all';
}

/**
 * 저장소의 인계 토큰을 **검증한 뒤** 돌려준다(형식·alg=none 거부). 아니면 `null`.
 * 진입 화면의 검증과 같은 규칙이다 — 다른 탭이 쓴 값을 받아들이는 자리도 같은 문턱을 넘어야 한다.
 */
export function readValidatedLocalStorageToken(): string | null {
  const token = readLocalStorageToken();
  return token && isValidJwtFormat(token) && isAlgAcceptable(token) ? token : null;
}
