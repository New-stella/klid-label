import { create } from 'zustand';

import { isKnownRole } from '@/lib/authz';
import { isPortalEmbedChannel } from '@/lib/buildChannel';
import { Channel, type Role, type TokenClaims } from '@/lib/api/types';

const SESSION_KEY = 'klid_jwt';

// [@design INT-013]
/**
 * 브라우저 저장소 보관은 **내부(관제) 채널 전용**이다.
 *
 * 포털 채널에서 저작도구는 Host 화면 안에서 실행되는 Remote 이고, access token 은
 * **Host 메모리에만** 둔다는 것이 포털이 제시한 비협상 조건이다(`INT-013`). 그 채널에서
 * 우리가 토큰을 저장소에 복사해 두면 두 가지가 함께 깨진다:
 *
 *   1. **보안** — Host 가 저장소에 두지 않기로 한 값을 우리가 저장소에 눕힌다. Host 는
 *      세션을 끝낼 때 자기 메모리만 비우므로, 우리가 흘려 둔 사본은 그 뒤에도 남는다.
 *   2. **정합** — 저장소에 남은 값은 Host 가 갱신한 순간 **죽은 토큰**이 된다. 그것을 읽는
 *      경로가 하나라도 생기면 어댑터(`features/auth/tokenHandoff`)를 도입한 이유가 사라진다.
 *
 * 그래서 저장소 접근을 세 함수로 좁히고 채널로 가른다. 판정은 `isPortalEmbedChannel()` 을
 * 재사용한다 — `import.meta.env` 를 다시 읽으면 채널 판정이 두 벌이 된다.
 *
 * ⚠ **메모리 보관(zustand `set`)은 두 채널 모두 그대로다.** 화면이 읽는 `claims`(역할·채널)가
 *   거기서 나오므로 이것까지 끄면 포털 채널에서 권한 판정이 통째로 빈다. 끄는 것은
 *   「브라우저 저장소에 눕히는 것」 하나뿐이다.
 *
 * 회귀 가드: `stores/__tests__/useAuthStorePortalChannel.test.ts`
 */
function persistToken(token: string): void {
  if (isPortalEmbedChannel()) return;
  sessionStorage.setItem(SESSION_KEY, token);
}

function forgetPersistedToken(): void {
  if (isPortalEmbedChannel()) return;
  sessionStorage.removeItem(SESSION_KEY);
}

function readPersistedToken(): string | null {
  if (isPortalEmbedChannel()) return null;
  return sessionStorage.getItem(SESSION_KEY);
}

interface AuthState {
  token: string | null;
  claims: TokenClaims | null;
  isHydrated: boolean;
  setToken: (token: string) => void;
  /**
   * Phase 2 (권한 자가 부여) — 새 토큰을 디코드하여 claims 와 함께 일괄 갱신한다.
   * `setToken` 과 동일 동작이지만 호출 의도(토큰 + 클레임 동시 교체)를 명확히 한다.
   */
  setTokenAndClaims: (token: string) => void;
  /**
   * 서버가 아는 <b>역할과 이름</b>을 claims 에 주입한다(토큰 원본은 유지).
   *
   * [@design SCREEN-002] [@design ADR-021] [@design ADR-063] [@design SHELL-001] [@design AC-1098]
   * ★인가의 진실원은 서버 LS_USER_ROLE(=GET /v1/me 응답)이지 <토큰 role 클레임>이 아니다.
   * 관제 토큰에는 우리 role 이 실리지 않아(authority 만 있고 role 클레임 없음) claims.role 이
   * 항상 null 이다. 그 상태로 두면 RoleGuard(가 claims.role 을 읽는다)가 관제 재방문 role 보유자를
   * 무권한으로 오인해 /role-claim 으로 튕긴다. SessionIngress 가 진입 시 /me 로 받은 서버 role 을
   * 여기로 주입해 가드가 서버 진실원을 보게 한다.
   *
   * ⚠ dev·포털 토큰은 role 클레임과 /me 가 같은 원천(userNo→LS_USER_ROLE)이라 주입값이 같아
   *   회귀가 없다. role=null 을 주입하면 claims.role 도 null 이 되어 가드가 /role-claim 으로
   *   보낸다(무권한 온보딩 — 의도된 동작).
   * ⚠ claims 가 아직 없으면(토큰 미적재) no-op. 토큰 원본·채널·exp 는 건드리지 않는다.
   *
   * <h3>★이름도 함께 받는다 — 역할만 취하고 이름을 버리지 않는다 (SHELL-001)</h3>
   * 화면 상단(GNB)에 표시하는 이름의 <b>진실원은 서버 응답</b>이고 인계 토큰의 이름 클레임은
   * 보조다. 관제 인계 토큰에 이름이 실려 오지 않아도 저작도구가 아는 이름이 표시돼야 하는데,
   * 예전에는 이 함수가 role 만 주입하고 이름을 버려 헤더가 대체 표기(「사용자」)에 고착됐다
   * (246 실측 2026-09-07 — 역할 배지는 「관리자」인데 이름만 「사용자」, 이니셜은 「사」).
   *
   * ⚠ <b>빈 이름으로 토큰 이름을 지우지 않는다.</b> 서버가 이름을 모를 수 있고(자동등록 직후),
   *   그때 토큰 클레임이라는 보조 조달원까지 잃으면 <b>고칠 결함이 그대로 재발</b>한다. 그래서
   *   비어 있지 않은 값이 왔을 때만 덮어쓴다 — 「진실원 우선, 보조 폴백」이 그 뜻이다.
   *
   * @param role 서버 인가 역할. `null` 이면 무권한(온보딩 대상).
   * @param name 서버가 보관한 표시 이름. `undefined`·빈 문자열이면 기존 값을 유지한다.
   */
  setServerRole: (role: Role | null, name?: string | null) => void;
  clear: () => void;
  hydrate: () => void;
}

// JWT payload base64url decode (보안: 무결성 검증은 BE에서 — FE는 표시용 클레임만 추출)
function decodeJwtPayload(token: string): TokenClaims | null {
  const parts = token.split('.');
  if (parts.length !== 3) return null;
  try {
    const padded = parts[1].replace(/-/g, '+').replace(/_/g, '/');
    const padding = padded.length % 4 === 0 ? '' : '='.repeat(4 - (padded.length % 4));
    const binary = atob(padded + padding);
    // UTF-8 safe decode — Array.from(binary) 는 surrogate pair 만 처리하고
    // 한글(EUC-KR/UTF-8 mix) 멀티바이트는 깨질 수 있으므로 TextDecoder 로 정확히 디코드.
    const bytes = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
    const json =
      typeof TextDecoder !== 'undefined'
        ? new TextDecoder('utf-8').decode(bytes)
        : decodeURIComponent(
            Array.from(binary)
              .map((c) => '%' + c.charCodeAt(0).toString(16).padStart(2, '0'))
              .join(''),
          );
    const raw = JSON.parse(json) as Record<string, unknown>;

    const sub = typeof raw.sub === 'string' ? raw.sub : '';
    // Phase 2 — role 클레임이 비어 있는 인증 토큰(권한 자가 부여 대기 상태)을 허용한다.
    //
    // ★모르는 역할 값 하나로 인증 전체를 버리지 않는다. 예전에는 우리가 아는 목록에 없는 값이
    //   오면 클레임 객체를 통째로 null 로 만들어, 그 사용자가 **로그인 자체를 못 했다**. 서버가
    //   역할을 새로 늘리는 것은 정상적인 일이고 그때마다 화면이 진입 불가가 되는 것은 fail-closed
    //   가 아니라 그냥 고장이다(실제로 관리자 역할이 늘었을 때 그 일이 일어났다).
    //
    //   그래서 모르는 값은 **역할 미부여로 낮춘다** — 인증은 살아 있고 권한만 없다. 권한이 없는
    //   상태의 처리는 이미 있다(내부 채널이면 역할 부여 안내로 보낸다). 인가 판정은 어차피
    //   서버가 소유하므로, 화면이 모르는 값에 권한을 주는 일은 이 경로 어디에도 없다.
    const rawRole = raw.role;
    const hasRole = rawRole !== undefined && rawRole !== null && rawRole !== '';
    const role: Role | null = hasRole && isKnownRole(rawRole) ? rawRole : null;
    // 관제 인계 토큰은 channel 클레임을 싣지 않는다 — BE 는 부재 시 INTERNAL 로 기본 처리한다
    //   (@design ADR-063). FE 도 동일하게 <부재는 INTERNAL>로 본다. 부재를 거부하면 관제 토큰이
    //   여기서 탈락해 claims=null → SessionIngress 가 「토큰 없음」으로 오판해 로그인 무한루프가
    //   된다(실측 2026-09-05). 값이 <있는데> 우리가 모르는 채널이면 종전대로 거부(BE valueOf 대칭).
    const channel =
      raw.channel === undefined || raw.channel === null
        ? Channel.INTERNAL
        : isChannel(raw.channel)
          ? raw.channel
          : null;
    const exp = typeof raw.exp === 'number' ? raw.exp : 0;
    const name = typeof raw.name === 'string' ? raw.name : undefined;

    if (!sub || !channel || !exp) return null;
    return { sub, role, channel, exp, name };
  } catch {
    return null;
  }
}

function isChannel(value: unknown): value is Channel {
  return typeof value === 'string' && (Object.values(Channel) as string[]).includes(value);
}

export const useAuthStore = create<AuthState>((set) => ({
  token: null,
  claims: null,
  isHydrated: false,
  setToken: (token: string) => {
    const claims = decodeJwtPayload(token);
    if (!claims) return; // 유효하지 않은 토큰은 저장하지 않음
    persistToken(token);
    set({ token, claims });
  },
  setTokenAndClaims: (token: string) => {
    const claims = decodeJwtPayload(token);
    if (!claims) return;
    persistToken(token);
    set({ token, claims });
  },
  setServerRole: (role: Role | null, name?: string | null) => {
    set((state) => {
      if (!state.claims) return {};
      // 비어 있지 않은 서버 이름만 덮어쓴다 — 빈 값으로 토큰 클레임(보조 조달원)을 지우지 않는다.
      const trimmed = typeof name === 'string' ? name.trim() : '';
      const nextName = trimmed.length > 0 ? trimmed : state.claims.name;
      return { claims: { ...state.claims, role, name: nextName } };
    });
  },
  clear: () => {
    forgetPersistedToken();
    set({ token: null, claims: null });
  },
  hydrate: () => {
    // 포털 채널은 저장소에 보관하지 않으므로 복원할 것이 없다 — 곧바로 hydration 완료로 넘어간다.
    // (Host 가 창구로 토큰을 내주므로 새로고침 복원은 Host 의 몫이다.)
    const stored = readPersistedToken();
    if (stored) {
      const claims = decodeJwtPayload(stored);
      // 만료된 토큰은 무시
      if (claims && claims.exp > Math.floor(Date.now() / 1000)) {
        set({ token: stored, claims, isHydrated: true });
        return;
      }
      // 만료됐으면 스토리지에서도 제거
      forgetPersistedToken();
    }
    set({ isHydrated: true });
  },
}));
