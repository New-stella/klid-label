// [@design ADR-012] [@design INT-013] [@design API-247] [@design API-246]
// [@design AC-1104] [@design AC-1105] [@design AC-1106] [@design NFR-013]
/**
 * 관제(내부) 채널 세션 연장 — 저장 형식 · 갱신/로그아웃 중계 호출 · 탭 간 동기화의 단일 지점.
 *
 * ## 왜 필요한가
 * 관제지원 웹은 저작도구를 **새 탭**으로 연다. 관제 access 토큰은 30분이고 관제 탭은 스스로 갱신한다.
 * 예전에는 진입 시 토큰을 한 번 복사해 그 복사본만 써서, 관제가 갱신해도 30분 뒤 401 로 튕겼다.
 * 이제 ①매 요청 저장소의 **현재값**을 쓰고 ②만료가 가까우면 **저작도구 서버의 갱신 중계**를 불러
 * ③새 토큰 쌍을 **관제 `saveTokens` 와 같은 키·형식**으로 저장한다(관제 탭이 그 값을 읽는다).
 *
 * ★ **브라우저는 관제 계정 서비스를 직접 부르지 않는다.** 갱신은 `POST /v1/auth/control-tokens`,
 *   로그아웃은 `DELETE /v1/auth/control-session` — 저작도구 서버가 관제로 중계한다.
 * ★ **refresh 토큰은 요청 본문으로만 보낸다.** 인증 헤더에 실으면 서버가 `type=refresh` 토큰을
 *   인증에서 거부한다([@design ADR-063] ⑦). 그래서 이 모듈은 인터셉터 없는 **별도 axios 인스턴스**를
 *   쓴다 — `apiClient` 를 쓰면 요청 인터셉터가 access 토큰을 붙이고, 401 인터셉터(로그아웃 이동)·
 *   선제 갱신과 재귀·경쟁한다.
 * ⚠ **포털 채널에서는 이 모듈의 어떤 것도 부르지 않는다**(서버도 404). 호출부가 채널로 가른다
 *   (`tokenHandoff` 의 창구 판정 · 앱 최상단의 감시 탑재 조건).
 *
 * 회귀 가드: `features/auth/__tests__/controlSession.test.ts` ·
 *            `lib/api/__tests__/clientControlSessionRefresh.test.ts` ·
 *            `features/auth/__tests__/ControlSessionMonitor.test.tsx`
 */
import axios from 'axios';

import { resolveApiBaseUrl } from '@/lib/api/baseUrl';
import { ApiError } from '@/lib/api/errors';
import { decodeJwtPayloadRaw } from '@/lib/jwtPayload';
import { useAuthStore } from '@/stores/useAuthStore';

import { terminateSession } from './sessionTermination';
import {
  LOCAL_STORAGE_TOKEN_KEY,
  LOCAL_STORAGE_USER_NM_KEY,
  isLocalStorageIngressEnabled,
  readValidatedLocalStorageToken,
} from './tokenIngress';

/** 관제가 쓰는 토큰 정보 키 — 관제와 합의된 이름이라 바꾸지 않는다. */
export const TOKEN_INFO_KEY = 'tokenInfo';

/**
 * 관제 `saveTokens` 가 쓰는 `tokenInfo` 필드 — **이름·순서 그대로**다. 관제 탭이 이 값을 읽으므로
 * 한 글자라도 다르면 관제 화면이 깨진다(예: `expiresAt` 을 읽어 즉시 로그아웃).
 */
export const TOKEN_INFO_FIELDS = [
  'access_token',
  'refresh_token',
  'authority',
  'userSido',
  'userSgg',
  'userOgCd',
  'expiresAt',
  'issuedAt',
  'sessionExpAlarm',
  'sessionTime',
  'eventDate',
  'rawVideoDate',
  'inAccessibleMenus',
] as const;

/** 갱신 중계 창구 — `API-247`. base URL(`/api/v1` 등) 뒤에 붙는다. */
export const CONTROL_TOKENS_PATH = '/auth/control-tokens';
/** 로그아웃 중계 창구 — `API-246`. */
export const CONTROL_SESSION_PATH = '/auth/control-session';

/** 요청 직전 남은 시간이 이 이하이면 선제 갱신한다(관제 웹과 같은 값). */
export const PREEMPTIVE_REFRESH_WINDOW_MS = 10 * 60 * 1000;
/** 팝업 임계(분) 기본값 — `tokenInfo.sessionExpAlarm` · 토큰 클레임이 모두 없을 때(관제와 같다). */
export const DEFAULT_SESSION_ALARM_MINUTES = 30;
/** 팝업 본문의 「로그인 후 N분」 기본값. */
export const DEFAULT_SESSION_TIME_MINUTES = 30;

const REFRESH_LOCK_NAME = 'klid-control-session-refresh';
const RELAY_TIMEOUT_MS = 10_000;
/** 서버의 관제 호출이 3초 수준이므로 그보다 조금 길게 — 사용자가 로그인 화면으로 가기 전 기다리는 시간이다. */
const LOGOUT_TIMEOUT_MS = 5_000;

/**
 * 중계 전용 axios 인스턴스 — **인터셉터가 없다.** 이것이 존재 이유다(파일 상단 ★ 참조).
 * 시험이 여기에 모의 어댑터를 붙일 수 있도록 내보낸다.
 */
export const controlSessionHttp = axios.create({
  baseURL: resolveApiBaseUrl(),
  withCredentials: true,
  timeout: RELAY_TIMEOUT_MS,
});

/* ------------------------------------------------------------------------- *
 * 저장소 — 관제와 같은 키·형식
 * ------------------------------------------------------------------------- */

function getItem(key: string): string | null {
  try {
    return typeof localStorage === 'undefined' ? null : localStorage.getItem(key);
  } catch {
    return null;
  }
}

function setItem(key: string, value: string): void {
  try {
    localStorage.setItem(key, value);
  } catch {
    /* 저장소 비활성·쿼터 초과 — 저장 실패는 다음 갱신에서 다시 시도된다 */
  }
}

function removeItem(key: string): void {
  try {
    localStorage.removeItem(key);
  } catch {
    /* 무시 */
  }
}

/** `tokenInfo` 를 객체로 읽는다. 없거나 깨졌으면 `null`. */
export function readTokenInfo(): Record<string, unknown> | null {
  const raw = getItem(TOKEN_INFO_KEY);
  if (!raw) return null;
  try {
    const value: unknown = JSON.parse(raw);
    return typeof value === 'object' && value !== null && !Array.isArray(value)
      ? (value as Record<string, unknown>)
      : null;
  } catch {
    return null;
  }
}

/**
 * 주어진 access 토큰과 **짝인** `tokenInfo` 만 돌려준다.
 *
 * ★ 짝을 확인하는 이유: 개발 로그인은 `klid-jwt-token` 만 쓰고 `tokenInfo` 를 쓰지 않는다. 앞선 관제
 *   세션의 `tokenInfo` 가 남아 있으면 **남의 refresh 토큰으로 개발 토큰을 덮어쓰거나**, 남의 만료
 *   시각으로 팝업을 띄우게 된다. 관제는 두 키를 언제나 함께 쓰므로 짝 확인이 정상 경로를 막지 않는다.
 */
function readPairedTokenInfo(accessToken: string | null): Record<string, unknown> | null {
  if (!accessToken) return null;
  const info = readTokenInfo();
  return info && info.access_token === accessToken ? info : null;
}

function nonEmptyString(value: unknown): string | null {
  return typeof value === 'string' && value.trim() !== '' ? value : null;
}

/** 저장소에 지금 놓인 refresh 토큰(`tokenInfo.refresh_token`). 없으면 `null`(개발 로그인 등). */
export function readRefreshToken(): string | null {
  return nonEmptyString(readPairedTokenInfo(getItem(LOCAL_STORAGE_TOKEN_KEY))?.refresh_token);
}

/**
 * 관제 `saveTokens(session, refresh)` 가 만드는 `tokenInfo` 와 **같은 값**을 만든다.
 *
 * 관제 코드와 글자 그대로 맞춘다 — 값은 새 access 토큰 클레임에서, `expiresAt`·`issuedAt` 은
 * 밀리초(`exp*1000`·`iat*1000`), `inAccessibleMenus` 는 `inaccessible_menus || []`.
 * `Number(...)` 는 관제의 `exp*1000`(JS 암묵 변환)과 같은 결과를 낸다. 클레임이 없는 필드는
 * `undefined` 로 두어 `JSON.stringify` 가 관제와 똑같이 키를 빼게 한다.
 */
export function buildControlTokenInfo(
  accessToken: string,
  refreshToken: string | null,
): Record<(typeof TOKEN_INFO_FIELDS)[number], unknown> {
  const c = decodeJwtPayloadRaw(accessToken) ?? {};
  return {
    access_token: accessToken,
    // ★ 새 refresh 토큰이 없는 갱신(`API-247` 201 + `refreshToken: null`)에서는 **칸을 비운다** —
    //   관제 `saveTokens(session, undefined)` 와 같은 결과다(`JSON.stringify` 가 키를 뺀다).
    //   빈 문자열을 넣으면 관제 탭이 「값이 있다」로 읽어 그 값으로 갱신을 시도한다.
    refresh_token: refreshToken ?? undefined,
    authority: c.authority,
    userSido: c.userSido,
    userSgg: c.userSgg,
    userOgCd: c.userOgCd,
    expiresAt: Number(c.exp) * 1000,
    issuedAt: Number(c.iat) * 1000,
    sessionExpAlarm: c.sessionExpAlarm,
    sessionTime: c.sessionTime,
    eventDate: c.eventDate,
    rawVideoDate: c.rawVideoDate,
    inAccessibleMenus: c.inaccessible_menus || [],
  };
}

/**
 * 새 토큰 쌍을 관제와 같은 키로 저장한다 — `klid-jwt-token` · `tokenInfo` · `userNm`.
 *
 * ⚠ 관제가 로그인 때만 쓰는 `authority`·`pwd_change_recommended`·`contextMenu` 단독 키는 쓰지 않는다
 *   (관제의 갱신도 쓰지 않는다).
 * ⚠ `userNm` 은 클레임이 **문자열일 때만** 쓴다 — 없으면 기존 값을 둔다.
 */
export function saveControlTokens(accessToken: string, refreshToken: string | null): void {
  setItem(LOCAL_STORAGE_TOKEN_KEY, accessToken);
  setItem(TOKEN_INFO_KEY, JSON.stringify(buildControlTokenInfo(accessToken, refreshToken)));
  const userNm = decodeJwtPayloadRaw(accessToken)?.userNm;
  if (typeof userNm === 'string') setItem(LOCAL_STORAGE_USER_NM_KEY, userNm);
}

/** 관제 `clearTokens` 와 같게 두 키를 지운다 — 같은 출처 관제 탭도 `storage` 이벤트로 로그아웃한다. */
export function clearControlTokens(): void {
  removeItem(TOKEN_INFO_KEY);
  removeItem(LOCAL_STORAGE_TOKEN_KEY);
}

/**
 * 강제 로그아웃 결말에서 공유 저장소의 토큰 키를 지운다 — **지우기 직전에 저장소를 다시 읽어**
 * 저장된 access 토큰이 이 탭이 끝내려는 토큰과 다르면 지우지 않는다([@design AC-1106]).
 *
 * ★ 재읽기가 필요한 이유: 이 탭이 거절당한 사이 다른 탭(관제 탭 포함)이 이미 새 토큰을 저장했을 수
 *   있다. 그때 지우면 **멀쩡한 세션을 끊는다** — 이 탭 하나가 나가는 것과 같은 출처의 모든 탭을
 *   로그아웃시키는 것은 결과의 크기가 다르다.
 * ⚠ 부르는 곳은 **거절·남은 시간 0** 두 결말뿐이다. 일시 장애(503·미도달)로 현재 토큰을 계속 쓰는
 *   경로와 401 뒤 갱신이 일시 장애로 실패해 이 탭만 나가는 경로에서는 부르지 않는다 — 그때 관제
 *   탭의 세션은 멀쩡할 수 있다.
 *
 * @param token 이 탭이 끝내려는 access 토큰(스토어를 비우기 **전에** 읽은 값).
 * @returns 실제로 지웠으면 `true`.
 */
export function clearControlTokensIfCurrent(token: string | null): boolean {
  if (!token) return false;
  const stored = getItem(LOCAL_STORAGE_TOKEN_KEY);
  if (stored !== null && stored !== token) return false;
  clearControlTokens();
  return true;
}

/* ------------------------------------------------------------------------- *
 * 중계 호출 — API-247 · API-246
 * ------------------------------------------------------------------------- */

export type RelayRefreshResult =
  /** `refreshToken` 이 `null` 이면 **더 갱신할 수 없는** 세션이다(관제가 새 refresh 를 주지 않았다). */
  | { kind: 'ok'; sessionToken: string; refreshToken: string | null }
  | { kind: 'rejected' }
  | { kind: 'unavailable' };

/**
 * 갱신 중계를 한 번 부른다. 결과는 **세 갈래뿐**이다 — 섞으면 일시 장애에 사용자를 내보내거나
 * 거절된 세션을 붙잡는다.
 *
 * - 2xx + `sessionToken` → `ok`
 * - 401 `CONTROL_SESSION_REJECTED`(관제가 거절) → `rejected`
 * - 그 밖(503 `CONTROL_SESSION_UNAVAILABLE` · 서버 미도달 · 형식 불일치 등) → `unavailable`
 *
 * ★ **성공 판정의 필수 조건은 `sessionToken` 하나다**([@design API-247]). 관제가 `error 0` 과
 *   `session_token` 은 주면서 `refresh_token` 을 주지 않으면 서버가 `refreshToken: null` 인 201 을
 *   내는데, 그것을 실패로 보면 **쓸 수 있는 새 access 토큰을 버리고** 「관제 서버에 연결할 수
 *   없습니다」를 오탐으로 띄운 뒤 남은 시간 내내 요청마다 갱신을 되풀이한다(실측 지적).
 *   그 세션은 더 갱신할 수 없을 뿐 만료 전까지는 정상이다 — 관제 웹과 같은 결말로 간다.
 * ⚠ 비멱등이다(관제가 refresh 토큰을 교체한다) — 여기서 자동 재시도하지 않는다.
 * ⚠ 토큰 원문을 로그에 싣지 않는다.
 */
export async function requestControlTokenRefresh(refreshToken: string): Promise<RelayRefreshResult> {
  try {
    const res = await controlSessionHttp.post(CONTROL_TOKENS_PATH, { refreshToken });
    const body = res.data as
      | { data?: { sessionToken?: unknown; refreshToken?: unknown } | null }
      | undefined;
    const sessionToken = nonEmptyString(body?.data?.sessionToken);
    if (sessionToken) {
      return { kind: 'ok', sessionToken, refreshToken: nonEmptyString(body?.data?.refreshToken) };
    }
    return { kind: 'unavailable' };
  } catch (e) {
    if (axios.isAxiosError(e) && e.response?.status === 401) return { kind: 'rejected' };
    return { kind: 'unavailable' };
  }
}

/**
 * 로그아웃 중계 — 관제 서버 세션을 끝낸다. **결말은 응답과 무관**하므로 실패를 던지지 않는다.
 * 헤더는 `Authorization: Bearer <access>` (관제로는 서버가 `x-access-token` 으로 넘긴다).
 */
export async function requestControlLogout(accessToken: string | null): Promise<void> {
  if (!accessToken) return;
  try {
    await controlSessionHttp.delete(CONTROL_SESSION_PATH, {
      headers: { Authorization: `Bearer ${accessToken}` },
      timeout: LOGOUT_TIMEOUT_MS,
    });
  } catch {
    /* 응답과 무관하게 저장소를 지우고 이동한다(관제지원 웹과 같은 처리) */
  }
}

/* ------------------------------------------------------------------------- *
 * 토큰 추종 · 갱신 오케스트레이션
 * ------------------------------------------------------------------------- */

/**
 * 이 탭이 **쥐고 있는** refresh 토큰 — 저장소의 access 토큰을 받아들일 때마다 그 짝을 기록한다.
 * 갱신 직전에 저장소 값과 달라져 있으면 다른 탭이 이미 갱신한 것이다.
 */
let heldRefreshToken: string | null = null;
/** 관제가 거절한 refresh 토큰 — 같은 값으로 다시 갱신하지 않는다. */
let rejectedRefreshToken: string | null = null;
/** 한 탭 안의 동시 갱신은 **하나의 Promise 를 공유**한다. */
let inflight: Promise<ControlRefreshOutcome> | null = null;

export type ControlRefreshOutcome =
  /** 이 탭이 갱신해 저장했다. */
  | { kind: 'refreshed'; token: string }
  /** 다른 탭이 이미 갱신해 그 값을 받아들였다(갱신 호출 없음). */
  | { kind: 'adopted'; token: string | null }
  /** refresh 토큰이 없다(개발 로그인 등) — 갱신하지 않는다. 오류가 아니다. */
  | { kind: 'skipped'; token: string | null }
  /** 일시 장애(503·미도달) — 저장하지 않고 현재 토큰으로 계속한다. */
  | { kind: 'unavailable'; token: string | null }
  /** 관제가 거절했고, 저장소를 다시 읽어도 다른 탭의 갱신이 없다. */
  | { kind: 'rejected' };

function currentStoreToken(): string | null {
  return useAuthStore.getState().token;
}

function adoptToken(token: string): void {
  if (currentStoreToken() !== token) useAuthStore.getState().replaceToken(token);
}

/** 저장소 현재값을 받아들이고 그 짝 refresh 토큰을 쥔다. */
function adoptFromStorage(): ControlRefreshOutcome {
  const stored = readValidatedLocalStorageToken();
  if (stored) adoptToken(stored);
  heldRefreshToken = readRefreshToken();
  return { kind: 'adopted', token: currentStoreToken() };
}

/**
 * 탭 사이 직렬화 — Web Locks 가 있으면 쓰고 없으면 그대로 실행한다.
 * ⚠ Web Locks 는 보안 컨텍스트(https·localhost) 전용이라 http 개발 서버에는 없다. 없어도 멈추지
 *   않는다 — 1차 방어는 아래 저장소 재읽기 비교다. 관제 탭은 이 잠금을 모르므로 완전한 배제는
 *   불가능하다(인지·수용).
 */
async function withRefreshLock<T>(fn: () => Promise<T>): Promise<T> {
  const locks =
    typeof navigator !== 'undefined'
      ? (navigator as Navigator & { locks?: LockManager }).locks
      : undefined;
  if (!locks || typeof locks.request !== 'function') return fn();
  let ran = false;
  try {
    return await locks.request(REFRESH_LOCK_NAME, () => {
      ran = true;
      return fn();
    });
  } catch (e) {
    if (ran) throw e;
    return fn();
  }
}

async function runRefresh(): Promise<ControlRefreshOutcome> {
  const held = heldRefreshToken;
  return withRefreshLock(async () => {
    const current = readRefreshToken();
    if (!current) return { kind: 'skipped', token: currentStoreToken() };
    // ① 갱신 직전 재읽기 — 쥐고 있던 값과 다르면 다른 탭이 이미 갱신했다. 호출하지 않는다.
    if (held !== null && current !== held) return adoptFromStorage();
    if (current === rejectedRefreshToken) return { kind: 'rejected' };

    const result = await requestControlTokenRefresh(current);
    if (result.kind === 'ok') {
      // ★ `refreshToken` 이 `null` 이면 저장 형식의 refresh 칸이 비고, 그러면 `readRefreshToken()` 이
      //   `null` 이라 이 세션은 이후 **갱신 창구를 부르지 않는다**(다음 호출이 `skipped`, 선제 갱신은
      //   `refreshable=false` 로 건너뛴다). 만료 시각에 로그아웃하는 결말만 남는다.
      saveControlTokens(result.sessionToken, result.refreshToken);
      adoptToken(result.sessionToken);
      heldRefreshToken = result.refreshToken;
      return { kind: 'refreshed', token: result.sessionToken };
    }
    if (result.kind === 'rejected') {
      // ② 거절 직후 재읽기 — 그사이 다른 탭이 갱신했으면 이 탭이 옛 refresh 토큰으로 거절당한
      //    것이다. 세션은 멀쩡하므로 끊지 않고 그 새 토큰으로 계속한다. 경로를 가리지 않는다.
      const again = readRefreshToken();
      if (again && again !== current) return adoptFromStorage();
      rejectedRefreshToken = current;
      return { kind: 'rejected' };
    }
    return { kind: 'unavailable', token: currentStoreToken() };
  });
}

/**
 * 세션을 갱신한다. 선제 갱신 · 401 뒤 갱신 · 팝업 「로그인 연장」이 모두 이것을 부른다.
 * ★ 이 함수는 **세션을 끝내지 않는다** — 결말(즉시 로그아웃·유지)은 결과를 받은 쪽이 정한다.
 */
export function refreshControlSession(): Promise<ControlRefreshOutcome> {
  if (inflight) return inflight;
  const pending = runRefresh().finally(() => {
    if (inflight === pending) inflight = null;
  });
  inflight = pending;
  return pending;
}

/**
 * 내부 채널 인계 창구의 조달 — **매 호출 저장소의 현재값**을 읽고, 스토어와 다르면 스토어를 맞춘다.
 *
 * - 세션이 없으면(스토어가 비었으면) 저장소 값을 받아들이지 않는다 — 세션은 진입 화면이 세운다.
 * - 인계를 저장소로 받지 않는 배포(URL·쿠키 전용)는 종전처럼 스토어 값이다.
 * - 저장소 값이 형식 검사를 통과하지 못하면 스토어 값을 그대로 쓴다.
 */
export function syncControlAccessToken(): string | null {
  const token = currentStoreToken();
  if (!token) return null;
  if (!isLocalStorageIngressEnabled()) return token;
  const stored = readValidatedLocalStorageToken();
  if (stored && stored !== token) {
    adoptToken(stored);
    heldRefreshToken = readRefreshToken();
    return currentStoreToken();
  }
  if (heldRefreshToken === null) heldRefreshToken = readRefreshToken();
  return token;
}

/* ------------------------------------------------------------------------- *
 * 만료 시각 · 요청 경로
 * ------------------------------------------------------------------------- */

export interface ControlSessionTiming {
  /** 만료 시각(ms). `tokenInfo.expiresAt` → 없으면 access `exp*1000`. */
  expiresAt: number;
  /** 팝업 임계(ms). `tokenInfo.sessionExpAlarm` → 클레임 → 30분. */
  alarmMs: number;
  /** 팝업 본문 「로그인 후 N분」. `tokenInfo.sessionTime` → 클레임 → 30. */
  sessionTimeMinutes: number;
  /** 이 세션이 갱신 가능한가(짝 `tokenInfo` 에 refresh 토큰이 있는가). */
  refreshable: boolean;
}

function toFiniteNumber(value: unknown): number | null {
  if (typeof value === 'number') return Number.isFinite(value) ? value : null;
  if (typeof value === 'string' && value.trim() !== '') {
    const n = Number(value);
    return Number.isFinite(n) ? n : null;
  }
  return null;
}

function toPositiveNumber(value: unknown): number | null {
  const n = toFiniteNumber(value);
  return n !== null && n > 0 ? n : null;
}

/** 지금 세션의 만료·임계. 세션이 없거나 만료 시각을 알 수 없으면 `null`. */
export function getControlSessionTiming(): ControlSessionTiming | null {
  const token = currentStoreToken();
  if (!token) return null;
  const claims = decodeJwtPayloadRaw(token);
  const info = readPairedTokenInfo(token);
  const exp = toFiniteNumber(claims?.exp);
  const expiresAt = toFiniteNumber(info?.expiresAt) ?? (exp !== null ? exp * 1000 : null);
  if (expiresAt === null) return null;
  const alarmMinutes =
    toPositiveNumber(info?.sessionExpAlarm) ??
    toPositiveNumber(claims?.sessionExpAlarm) ??
    DEFAULT_SESSION_ALARM_MINUTES;
  const sessionTimeMinutes =
    toPositiveNumber(info?.sessionTime) ??
    toPositiveNumber(claims?.sessionTime) ??
    DEFAULT_SESSION_TIME_MINUTES;
  return {
    expiresAt,
    alarmMs: alarmMinutes * 60 * 1000,
    sessionTimeMinutes,
    refreshable: nonEmptyString(info?.refresh_token) !== null,
  };
}

/** 관제가 갱신을 거절해 세션을 끝냈을 때 요청이 받는 오류. */
export function controlSessionRejectedError(): ApiError {
  return new ApiError({
    errorCode: 'CONTROL_SESSION_REJECTED',
    status: 401,
    message: '관제 세션이 만료되었습니다. 다시 로그인해 주세요.',
  });
}

/**
 * 요청 직전 선제 갱신 — 남은 시간 ≤10분이고 갱신 가능하면 갱신한 뒤 새 토큰을 돌려준다.
 * 동시 요청은 같은 갱신을 기다린다(갱신 호출 1회).
 *
 * - 거절 → **즉시** 세션을 끝내고 요청을 오류로 끝낸다(남은 시간 무관).
 * - 일시 장애·갱신 불가 → 현재 토큰으로 보낸다. 로그아웃은 남은 시간 0 에서만.
 */
export async function prepareControlRequestToken(token: string): Promise<string | null> {
  const timing = getControlSessionTiming();
  if (!timing || !timing.refreshable) return token;
  if (timing.expiresAt - Date.now() > PREEMPTIVE_REFRESH_WINDOW_MS) return token;
  const outcome = await refreshControlSession();
  if (outcome.kind === 'rejected') {
    terminateSession({ clearStoredControlTokens: true });
    throw controlSessionRejectedError();
  }
  return outcome.token ?? token;
}

/**
 * 401 뒤 갱신의 결과 — 호출자(요청 인터셉터)가 결말을 정한다.
 *
 * `rejected` 를 따로 싣는 이유는 **강제 로그아웃의 뒷정리가 갈리기 때문**이다. 관제가 거절한
 * 것이면 같은 출처 저장소의 토큰 키까지 지우고(관제 탭도 함께 로그아웃), 일시 장애로 실패한
 * 것이면 이 탭만 나간다 — 그때 관제 탭의 세션은 멀쩡할 수 있다([@design AC-1106]).
 */
export interface UnauthorizedRefreshResult {
  /** 갱신·채택으로 얻은 새 토큰. 실패면 `null`(호출자가 로그아웃한다). */
  token: string | null;
  /** 관제가 거절했다([@design API-247] 401 `CONTROL_SESSION_REJECTED`). */
  rejected: boolean;
}

/**
 * 401 뒤 갱신 — 성공(또는 다른 탭의 갱신 채택)이면 새 토큰, 아니면 `null`(호출자가 로그아웃한다).
 * refresh 토큰이 없으면(개발 로그인) 갱신하지 않고 `null` — 종전 401 결말 그대로.
 */
export async function refreshAfterUnauthorized(): Promise<UnauthorizedRefreshResult> {
  if (!readRefreshToken()) return { token: null, rejected: false };
  const outcome = await refreshControlSession();
  if (outcome.kind === 'refreshed' || outcome.kind === 'adopted') {
    return { token: outcome.token, rejected: false };
  }
  return { token: null, rejected: outcome.kind === 'rejected' };
}

/* ------------------------------------------------------------------------- *
 * 탭 동기화 — storage 이벤트
 * ------------------------------------------------------------------------- */

/**
 * 다른 탭(관제 탭 포함)의 갱신·로그아웃을 따라간다.
 *
 * - `klid-jwt-token` 값이 바뀌면 → 스토어를 맞추고 `onChange`(팝업 닫기·재표시 억제 초기화).
 * - `klid-jwt-token` 이 사라지면(관제 로그아웃) → 강제 로그아웃.
 * - `tokenInfo` 는 **있다가 사라졌을 때만** 로그아웃 — 개발 로그인은 처음부터 없다.
 * - 세션이 없으면(로그인 전 화면) 아무것도 하지 않는다.
 *
 * @returns 구독 해제 함수
 */
export function installControlSessionStorageSync(onChange?: () => void): () => void {
  if (typeof window === 'undefined') return () => {};
  const handler = (e: StorageEvent) => {
    if (e.storageArea !== null && e.storageArea !== localStorage) return;
    if (!isLocalStorageIngressEnabled()) return;
    const token = currentStoreToken();
    if (!token) return;

    if (e.key === null || e.key === LOCAL_STORAGE_TOKEN_KEY) {
      // 그 키의 이벤트면 이벤트가 실어 온 새 값이 판정 근거다. 전체 비우기(`key === null`)는 값이
      // 실려 오지 않으므로 저장소를 읽는다.
      const now = e.key === LOCAL_STORAGE_TOKEN_KEY ? e.newValue : getItem(LOCAL_STORAGE_TOKEN_KEY);
      if (now === null) {
        terminateSession();
        return;
      }
      const stored = readValidatedLocalStorageToken();
      if (stored && stored !== token) {
        adoptToken(stored);
        heldRefreshToken = readRefreshToken();
      }
      onChange?.();
      return;
    }
    if (e.key === TOKEN_INFO_KEY) {
      if (e.oldValue !== null && e.newValue === null) {
        terminateSession();
        return;
      }
      onChange?.();
    }
  };
  window.addEventListener('storage', handler);
  return () => window.removeEventListener('storage', handler);
}

/** 시험 전용 — 모듈 스코프 상태를 초기화한다. */
export function resetControlSessionForTest(): void {
  heldRefreshToken = null;
  rejectedRefreshToken = null;
  inflight = null;
}
