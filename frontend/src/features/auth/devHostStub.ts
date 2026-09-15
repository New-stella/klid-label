// [@design INT-013] [@design SCREEN-004]
/**
 * Host 를 대신하는 **개발 환경 전용 임시 창구** — 포털 채널 산출물을 Host 없이 단독으로 띄울 때만 산다.
 *
 * ## 왜 필요한가
 *
 * 포털 채널 산출물은 **Host 가 있다고 전제한다.** 그 전제가 성립하지 않는 형상이 하나 있다 —
 * 포털 연동 배포본을 **포털 없이 단독으로 띄워 확인하는 개발 환경**이다. 그 형상에서는 두 가지가
 * 동시에 막힌다(`INT-013` 「Host 없이 단독으로 띄우는 경우」).
 *
 *   1. **화면에 닿지 못한다** — 마운트 경로가 라우터 기준 경로라, 그 밖의 주소로 들어오면 어떤
 *      화면도 걸리지 않는다. 주소창의 `/` 는 빈 화면이 된다.
 *   2. **토큰을 얻지 못한다** — 인계 창구가 없고, **없을 때 브라우저 저장소로 폴백하지 않는 것이
 *      규정**이다(`features/auth/tokenHandoff` 상단). 개발용 토큰을 발급해도 요청에 실리지 않아
 *      전 API 가 401 로 떨어진다.
 *
 * 이 모듈이 그 둘을 함께 메운다. 둘을 한 파일에 두는 것은 편의가 아니라 **역할이 같기 때문**이다 —
 * Host 는 원래 「우리를 어디에 마운트할지」와 「토큰을 어떻게 건넬지」를 함께 소유한다.
 *
 * ## 지켜야 하는 것 (되돌리기 전에 읽을 것)
 *
 * - **계약을 새로 만들지 않는다.** 임시 창구는 실제 Host 가 쓰는 것과 **같은 네 이름**을 그대로
 *   구현하고 같은 등록 창구(`registerHostTokenHandoff`)를 통과한다. 그래야 실제 Host 계약을
 *   어겼을 때 개발 단계에서 드러난다 — 따로 판 우회로는 그 검증을 못 한다.
 * - **본체의 불변식을 되살리지 않는다.** 포털 채널에서 `useAuthStore` 가 브라우저 저장소를 쓰지
 *   않는 것도, 창구가 없을 때 스토어로 폴백하지 않는 것도 그대로다. 여기서 하는 일은 **창구를
 *   제공**하는 것이지 폴백을 여는 것이 아니다. 그래서 토큰은 **이 모듈이 자기 키로** 보관한다 —
 *   본체의 인계 키(`LOCAL_STORAGE_TOKEN_KEY`)나 보관 키(`klid_jwt`)를 재사용하면
 *   「본체가 저장소를 안 쓴다」가 흐려져 다음 사람이 그 불변식을 읽어 낼 수 없다.
 * - **이동 목적지는 산출 시점에 굳은 값에서만 나온다.** 질의 문자열·해시·Host 가 건넨 값은
 *   목적지에 섞지 않는다 — 섞으면 기준 경로가 곧 이동 표면이 되어 바깥 주소로 끌려간다
 *   (`lib/remoteMount` 의 `PORTAL_MOUNT_BASENAME` 에 이미 걸려 있는 금지와 같은 것이다).
 * - **단독 구동 진입점에만 둔다.** 진입점은 둘이다 — 문서를 소유하는 `main.tsx` 와 Host 안에
 *   실리는 `remote/AuthoringRemote.tsx`. 이 모듈을 **앞쪽에서만** 부르면 실제 Host 안에서는
 *   **구조적으로 활성화될 수 없다.** 조건 검사에 기대지 않고 배치로 보장하는 편이 안전하다.
 *
 * ## 운영 산출물에 포함되지 않는다
 *
 * 이 장치는 임의 권한의 토큰을 요청에 실어 주므로, 운영 산출물에 남으면 **그 자체가 인증 우회
 * 경로**다. 그래서 호출부(`main.tsx` · `DevLoginPage`)는 `import.meta.env.DEV` 를 **먼저** 보고
 * 그 안에서 동적 import 한다 — 산출 시점에 굳는 값이라 운영 빌드에서는 분기째 지워져 이 모듈이
 * 청크로 방출되지 않는다.
 *
 * ⚠ **개발용 로그인 노출 값(`VITE_DEV_LOGIN_ENABLED`) 하나에 기대면 안 된다.** 폐쇄망 반입 산출은
 *   그 값을 **기본으로 켜서** 만들기 때문에(`lib/devLogin` 주석), 그 값만으로 가르면 **반입
 *   산출물에 이 장치가 들어간다.** 아래 `isDevStandaloneHostEnabled()` 가 그 값도 함께 보지만
 *   그것은 「운영자가 명시적으로 껐다」를 존중하기 위한 것이고, 산출물에서 빼는 축은
 *   `import.meta.env.DEV` 다. 두 축을 혼동하지 말 것.
 *
 * 회귀 가드: `features/auth/__tests__/devHostStub.test.ts`
 */
import { isPortalEmbedChannel } from '@/lib/buildChannel';
import { isDevLoginEnabled } from '@/lib/devLogin';
import { PORTAL_MOUNT_BASENAME } from '@/lib/remoteMount';
import { useAuthStore } from '@/stores/useAuthStore';

import { registerHostTokenHandoff, type TokenHandoffGateway } from './tokenHandoff';

/**
 * 임시 창구가 **자기 몫으로** 토큰을 두는 자리.
 *
 * 본체가 쓰는 어떤 키와도 겹치지 않는다(`klid-jwt-token` 인계 키 · `klid_jwt` 보관 키). 이름에
 * `dev-host` 가 들어 있는 것도 의도다 — 저장소를 들여다본 사람이 「본체가 흘린 사본」과
 * 「Host 대역이 들고 있는 것」을 구분할 수 있어야 한다.
 *
 * `sessionStorage` 인 이유: 새로고침(같은 탭)만 견디면 충분하고, 탭을 닫으면 사라지는 편이
 * 개발 장비에 토큰이 오래 남지 않아 안전하다.
 */
export const DEV_HOST_TOKEN_STORAGE_KEY = 'klid-dev-host-token';

/**
 * 주소창의 `/` 처럼 **가리키는 화면이 없는** 진입을 받아 줄 자리.
 *
 * 마운트 경로 아래의 `/` 로 보내면 내부(관제) 채널의 루트 라우트에 걸려 포털 사용자에게
 * 접근 거부 안내가 뜬다(그 라우트는 대시보드로 보낸다). 진입 페이지는 **채널을 스스로 판정**해
 * 토큰이 있으면 포털 홈으로, 없으면 개발용 로그인으로 보내므로 이 자리에 정확히 맞는다.
 */
const STANDALONE_ROOT_ENTRY = '/ingress';

/** Host 가 메모리에 들고 있는 것을 모사한다 — 조회는 언제나 이 값에서 나간다. */
let heldToken: string | null = null;

function readHeldToken(): string | null {
  try {
    return sessionStorage.getItem(DEV_HOST_TOKEN_STORAGE_KEY);
  } catch {
    // 프라이빗 모드·쿼터 초과 등 — 개발 편의 장치라 조용히 없는 것으로 본다.
    return null;
  }
}

function writeHeldToken(token: string): boolean {
  try {
    sessionStorage.setItem(DEV_HOST_TOKEN_STORAGE_KEY, token);
    return true;
  } catch {
    return false;
  }
}

function eraseHeldToken(): void {
  try {
    sessionStorage.removeItem(DEV_HOST_TOKEN_STORAGE_KEY);
  } catch {
    /* 지우지 못해도 메모리 쪽은 아래에서 비운다 */
  }
}

/**
 * 이 장치가 살아 있어야 하는 형상인지 — **세 축을 모두** 만족할 때만이다.
 *
 * | 축 | 이유 |
 * |---|---|
 * | 개발 빌드(`import.meta.env.DEV`) | 산출물에서 빼는 축. 운영 빌드에서 분기째 지워진다 |
 * | 포털 채널 산출물 | Host 를 전제하는 산출물에서만 대역이 필요하다 |
 * | 개발용 로그인 노출 | 운영자가 명시적으로 껐다면 이 장치도 함께 꺼진다 |
 */
export function isDevStandaloneHostEnabled(): boolean {
  return import.meta.env.DEV && isPortalEmbedChannel() && isDevLoginEnabled();
}

/**
 * Host 대역 인계 창구 — 실제 Host 가 주입하는 것과 **같은 모양**이다.
 *
 * ⚠ 네 이름 중 하나라도 빠지거나 다르면 `registerHostTokenHandoff` 가 등록을 거부한다.
 *   그 거부는 의도된 것이며, 여기서 이름을 새로 지으면 실제 Host 계약과 갈린다.
 */
export const devHostGateway: TokenHandoffGateway = {
  getAccessToken() {
    return heldToken;
  },

  /**
   * 임시 창구에는 **재발급 능력이 없다** — 개발용 토큰을 발급하는 것은 개발용 로그인 화면이고
   * 이 대역은 그것을 받아 들고 있을 뿐이다. 없는 능력을 있는 척하지 않되, 호출부가 채널을
   * 분기하지 않아도 되도록 현재 값을 그대로 돌려준다(내부 채널 창구와 같은 관례).
   */
  async refresh() {
    return heldToken;
  },

  /**
   * 인증이 끊겼다 — Host 라면 자기 재로그인 흐름을 시작한다. 대역이 할 수 있는 그 대응은
   * 「들고 있던 것을 버리고 개발용 로그인으로 보내는 것」이다.
   *
   * ⚠ 지금 이 창구를 부르는 곳은 없다(요청 인터셉터는 토큰 만료를 자기 경로로 처리한다 —
   *   근거는 `lib/api/client` 주석). 계약을 빠짐없이 구현해 두는 자리이며, 나중에 배선되면
   *   그때 이 동작이 그대로 쓰인다.
   */
  onUnauthorized() {
    clearDevHostToken();
    if (typeof window !== 'undefined') {
      window.location.replace(`${PORTAL_MOUNT_BASENAME}/dev/login`);
    }
  },

  notifyActivity() {
    /* no-op — 알릴 상대가 없다. 계약을 채우기 위한 자리다. */
  },
};

/**
 * 개발용 로그인이 발급한 토큰을 대역에게 건넨다.
 *
 * 구조가 「본체가 저장소를 읽는다」가 아니라 **「Host 역할을 하는 쪽이 토큰을 갖고 창구로
 * 건넨다」**여야 하므로, 발급 화면은 저장소에 쓰지 않고 이 함수를 부른다.
 *
 * @returns 보관에 성공했는지. 실패하면 발급 화면이 저장 실패로 안내한다.
 */
export function seedDevHostToken(token: string): boolean {
  if (!isDevStandaloneHostEnabled()) return false;
  if (!writeHeldToken(token)) return false;
  heldToken = token;
  // 창구가 아직 등록되지 않았을 수도 있다(진입점을 거치지 않은 경로). 등록은 멱등이다.
  registerHostTokenHandoff(devHostGateway);
  return true;
}

/** 대역이 들고 있던 것을 버린다 — 저장소·메모리·본체 세션을 함께 비운다. */
export function clearDevHostToken(): void {
  eraseHeldToken();
  heldToken = null;
  useAuthStore.getState().clear();
}

/**
 * 포털 iframe 안에서 떴는데 보관한 토큰이 없거나 만료됐으면 **개발용 토큰을 스스로 받아 둔다**.
 *
 * 토큰 보관소는 탭마다 따로라, 포털 화면에서 연 iframe 에는 로그인 화면에서 받아 둔 토큰이 없다.
 * 그대로 두면 포털 안 저작도구 자리에 늘 「인증이 필요합니다」가 뜬다. 실제 Host 는 마운트할 때
 * 제 토큰을 건네므로, 이것은 로컬에서 포털과 나란히 띄워 볼 때만 그 몫을 대신한다.
 * 역할은 로그인 화면의 기본값(포털 사용자)이다.
 *
 * ⚠ 개발 단독 구동 + iframe 안일 때만 돈다. 실패하면 조용히 넘어가 원래 안내가 뜬다.
 */
export async function seedFramedDevToken(): Promise<void> {
  if (!isDevStandaloneHostEnabled()) return;
  if (typeof window === 'undefined' || window.self === window.top) return;
  if (isHeldTokenAlive(readHeldToken())) return;
  try {
    const res = await fetch('/api/v1/dev/tokens', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ role: 'PORTAL_USER', channel: 'PORTAL' }),
    });
    if (!res.ok) return;
    const body = (await res.json()) as { data?: { token?: unknown } };
    const token = body.data?.token;
    if (typeof token === 'string' && token.length > 0) writeHeldToken(token);
  } catch {
    // 서버가 꺼져 있으면 원래 안내(인증 필요)로 둔다
  }
}

/** 보관한 토큰이 아직 살아 있는지 — 서명은 보지 않고 만료 시각만 읽는다(판정은 서버 몫) */
function isHeldTokenAlive(token: string | null): boolean {
  if (token === null) return false;
  try {
    const payload = JSON.parse(atob(token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/'))) as {
      exp?: number;
    };
    return typeof payload.exp === 'number' && payload.exp > Math.floor(Date.now() / 1000) + 60;
  } catch {
    return false;
  }
}

/**
 * 대역 창구를 등록하고, 보관해 둔 토큰이 있으면 **본체에 건넨다**.
 *
 * 새로고침하면 본체의 메모리는 비고 포털 채널은 저장소에서 복원하지 않으므로, 그대로 두면
 * 매번 다시 로그인해야 한다. 실제 Host 도 마운트 시점에 자기가 들고 있는 토큰을 Remote 에
 * 건네므로 이 동작이 그 재현이다 — 본체가 저장소를 읽는 것이 아니라 **대역이 건네는** 것이다.
 *
 * ⚠ 만료된 토큰은 건네지 않고 버린다. 건네면 라우트 가드가 「만료」로 판정해 상위 시스템
 *   로그인으로 보내려 하는데, 단독 구동 환경에는 갈 상위 시스템이 없어 화면이 멈춘다.
 */
export function installDevHostTokenHandoff(): boolean {
  if (!isDevStandaloneHostEnabled()) return false;

  if (!registerHostTokenHandoff(devHostGateway)) {
    // 토큰 값은 절대 싣지 않는다 — 등록 실패 사실만 남긴다.
    console.error('[devHostStub] Host 대역 인계 창구 등록에 실패했다.');
    return false;
  }

  heldToken = readHeldToken();
  if (heldToken !== null) {
    const auth = useAuthStore.getState();
    auth.setTokenAndClaims(heldToken);
    const claims = useAuthStore.getState().claims;
    const alive = claims !== null && claims.exp > Math.floor(Date.now() / 1000);
    if (!alive) clearDevHostToken();
  }

  console.info('[devHostStub] Host 대역 인계 창구를 등록했다 — 개발 단독 구동 전용.');
  return true;
}

/**
 * 주소 하나를 마운트 경로 아래로 옮긴 결과. 옮길 필요가 없으면 `null`.
 *
 * 순수 함수로 뽑아 둔 것은 **이동 목적지가 무엇으로 만들어지는지**가 이 장치에서 가장 위험한
 * 지점이기 때문이다. 목적지의 **밑동은 언제나 산출 시점에 굳은 상수**(`PORTAL_MOUNT_BASENAME`)이고,
 * 실행 중 입력에서 오는 것은 「현재 문서의 경로」뿐이며 그것도 앞에 상수를 붙여 **같은 출처의
 * 하위 경로**로만 만들어진다. 질의 문자열·해시는 목적지에 섞지 않는다.
 */
export function resolveStandaloneMountRedirect(pathname: string): string | null {
  if (!isDevStandaloneHostEnabled()) return null;
  if (
    pathname === PORTAL_MOUNT_BASENAME ||
    pathname.startsWith(`${PORTAL_MOUNT_BASENAME}/`)
  ) {
    return null;
  }
  const normalized = normalizeSameOriginPath(pathname);
  const tail = normalized === '/' ? STANDALONE_ROOT_ENTRY : normalized;
  return `${PORTAL_MOUNT_BASENAME}${tail}`;
}

/**
 * 경로를 「슬래시 하나로 시작하는 같은 출처 경로」로 정규화한다.
 *
 * `//evil.example` 이나 `/\evil.example` 처럼 **출처를 바꾸는 형태로 읽힐 수 있는 앞머리**를
 * 하나의 슬래시로 접는다. 앞에 상수를 붙이므로 원리적으로도 출처를 벗어날 수 없지만, 방어를
 * 목적지 조립 **직전**에 한 번 더 두는 편이 이 함수를 재사용할 때 안전하다.
 */
function normalizeSameOriginPath(pathname: string): string {
  if (typeof pathname !== 'string' || pathname.length === 0) return '/';
  const forwardOnly = pathname.replace(/\\/g, '/');
  const collapsed = forwardOnly.replace(/^\/+/, '/');
  return collapsed.startsWith('/') ? collapsed : `/${collapsed}`;
}

/**
 * 기준 경로 밖으로 들어온 진입을 마운트 경로 아래로 보낸다.
 *
 * ⚠ 이것은 「이탈 분기 네 곳은 제자리 안내」(`INT-013`)와 **다른 축**이다. 그건 앱이 그린 화면
 *   안에서의 인증·권한 분기이고, 이것은 **앱이 시작되기 전의 진입 지점 보정**이다. 그 규정을
 *   되돌리는 것이 아니다.
 *
 * @returns 이동을 시작했는지. `true` 면 문서가 곧 교체되므로 **렌더하지 않는다**(빈 화면이
 *   한 번 깜빡이는 것을 막는다).
 */
export function applyStandaloneMountRedirect(): boolean {
  if (typeof window === 'undefined') return false;
  const target = resolveStandaloneMountRedirect(window.location.pathname);
  if (target === null) return false;
  window.location.replace(target);
  return true;
}
