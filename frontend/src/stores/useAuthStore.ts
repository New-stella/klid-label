import { create } from 'zustand';

import {
  isLocalStorageIngressEnabled,
  readValidatedLocalStorageToken,
} from '@/features/auth/tokenIngress';
import { isKnownRole } from '@/lib/authz';
import { isPortalEmbedChannel } from '@/lib/buildChannel';
import { decodeJwtPayloadRaw } from '@/lib/jwtPayload';
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

// [@design ADR-063] [@design UC-041] [@design SEQ-034] [@design AC-1016] [@design AC-1017]
/**
 * 서버 인가 역할(<code>GET /v1/me</code>)의 <b>확보 상태</b>.
 *
 * <h3>왜 별도 상태가 필요한가 — `isHydrated` 를 넓히지 않은 이유</h3>
 * 토큰 복원(`isHydrated`)과 서버 역할 확보는 <b>다른 축</b>이다. `isHydrated` 는 앱 최상단
 * (`App`)이 라우터 렌더 전체를 막는 데 쓰므로, 그 의미를 서버 조회 완료까지 넓히면
 * <b>첫 화면 표시가 통째로 그 응답만큼 늦어진다</b>. 기다려야 하는 것은 「역할을 판정하는
 * 자리」 하나뿐이라 그 자리만 보게 축을 따로 둔다.
 *
 * <h3>초기값이 `'idle'` 인 것은 의도다</h3>
 * `'pending'` 으로 시작하면 <b>스토어에 claims 를 직접 심는 모든 화면 시험</b>이 영구 스피너에
 * 걸린다. 확보 절차(`features/auth/sessionBootstrap`)가 <b>요청을 시작하는 순간</b>
 * `'pending'` 을 동기로 세우고, 그 세팅은 토큰 복원과 <b>같은 렌더 배치</b>에서 일어나므로
 * 「복원은 끝났는데 아직 조회를 시작하지 않은」 창은 화면에 나타나지 않는다.
 *
 * <h3>★`'idle'` 과 `'unacquired'` 를 가른 이유 (2026-09-08)</h3>
 * 예전에는 `'idle'` 하나가 <b>「세션이 없다」와 「세션은 있는데 확보가 누락됐다」를 겸했다</b>.
 * 앞쪽은 통과시켜야 하고(로그인하지 않은 사용자를 스피너에 가두지 않는다) 뒤쪽은 판정을
 * 멈춰야 하는데, 한 값이라 <b>뒤쪽이 조용히 통과</b>했다 — 서버 역할을 한 번도 확인하지 않은
 * 채 화면이 열리는 fail-open 이다. 그래서 <b>세션이 실제로 수립되는 자리</b>
 * (`setToken`·`setTokenAndClaims`·`hydrate` 의 복원 성공)에서만 `'unacquired'` 를 세운다.
 *
 * ⚠ <b>초기값을 `'unacquired'` 로 올리지 말 것.</b> 그러면 `'pending'` 초기값과 똑같은 사고가
 *   난다 — 스토어에 상태를 직접 심는 화면 시험이 전부 영구 스피너에 걸린다. 이 값은
 *   <b>세션 수립 행위가 남기는 표식</b>이지 기본 상태가 아니다.
 *
 * - `idle`        확보 대상이 아니다 — 세션이 없거나 아직 수립되지 않았다. <b>통과</b>.
 * - `unacquired`  세션은 섰는데 확보 결과가 없다. <b>역할 판정에 도달하지 않는다</b>.
 * - `pending`     조회 중 — <b>역할 판정을 미룬다</b>.
 * - `ready`       서버 값이 claims 에 주입됐다.
 * - `failed`      확인하지 <b>못했다</b>. ★「역할 없음」이 아니다 — 이 둘을 뭉개면 서버 장애가
 *                 *"아직 관리자가 없습니다"* 로 표시된다(2026-09-07 에 고친 결함).
 */
export type ServerRoleStatus = 'idle' | 'unacquired' | 'pending' | 'ready' | 'failed';

interface AuthState {
  token: string | null;
  claims: TokenClaims | null;
  isHydrated: boolean;
  /** 서버 인가 역할 확보 상태. 판정 대기·실패 구분의 단일 진실원. */
  serverRoleStatus: ServerRoleStatus;
  setToken: (token: string) => void;
  /**
   * Phase 2 (권한 자가 부여) — 새 토큰을 디코드하여 claims 와 함께 일괄 갱신한다.
   * `setToken` 과 동일 동작이지만 호출 의도(토큰 + 클레임 동시 교체)를 명확히 한다.
   */
  setTokenAndClaims: (token: string) => void;
  /**
   * [@design ADR-012] [@design INT-013] [@design AC-1104]
   * <b>같은 사용자의 토큰만</b> 갈아끼운다 — 관제 채널 세션 갱신(이 탭이든 다른 탭이든)이 부른다.
   *
   * ★ `setToken` 을 쓰지 않는 이유: 그것은 세션을 <b>새로 세우는</b> 행위라 확보 상태를
   *   `'unacquired'` 로 되돌리고, 그러면 역할 판정 자리가 스피너로 떨어진다. 관제 access 토큰은
   *   30분마다 갈리므로 갱신마다 화면이 깜빡이게 된다. 역할의 진실원은 서버(`/v1/me`)이고
   *   <b>사용자가 같으면 역할도 같다</b> — 주입해 둔 서버 역할·이름을 그대로 둔다.
   * ⚠ 사용자(`sub`)가 바뀌었거나 세션이 없으면 `setToken` 과 똑같이 새 세션으로 세운다
   *   (다른 사람의 역할을 물려주지 않는다).
   * ⚠ 역할 확보가 <b>진행 중</b>이면 그 조회는 옛 토큰으로 나간 것이라 결과가 새 토큰 세션에
   *   앉지 않는다(`sessionBootstrap.applyToSession` 이 토큰으로 대조한다). 그대로 두면 확보 상태가
   *   `'pending'` 에 고착되므로, 그때만 `'unacquired'` 로 되돌려 한 번 더 묻게 한다.
   *
   * @returns 새 토큰을 적재했으면 `true`, 해독 실패로 무시했으면 `false`.
   */
  replaceToken: (token: string) => boolean;
  /**
   * 서버가 아는 <b>역할과 이름</b>을 claims 에 주입한다(토큰 원본은 유지).
   *
   * [@design SCREEN-002] [@design ADR-021] [@design ADR-063] [@design SHELL-001] [@design AC-1098]
   * ★인가의 진실원은 서버 LS_USER_ROLE(=GET /v1/me 응답)이지 <토큰 role 클레임>이 아니다.
   * 관제 인계 토큰의 role 클레임은 <관제 자신의 역할값>이라 우리 역할 집합(ADMIN/REVIEWER/
   * WORKER/PORTAL_USER)과 겹치지 않을 수 있고, 그때 디코더가 그것을 <역할 미부여로 낮춰>
   * claims.role 이 null 이 된다(모르는 값 하나로 인증 전체를 버리지 않기 위한 의도된 동작 —
   * 위 decodeJwtPayload 주석). 그 상태로 두면 RoleGuard(가 claims.role 을 읽는다)가 관제
   * 재방문 role 보유자를 무권한으로 오인해 /role-claim 으로 튕긴다.
   *
   * ★<b>주입은 화면을 다시 불러올 때마다 다시 일어나야 한다.</b> 이 값은 메모리에만 살고
   * 저장소에 보관하지 않으므로(그래야 서버가 역할을 회수했을 때 옛 값이 남지 않는다)
   * 새로고침이면 통째로 사라진다. 그래서 호출자는 진입 화면 하나가 아니라
   * <b>`features/auth/sessionBootstrap.ensureServerRole` 한 곳</b>이고, 그것을 앱 부팅과
   * 진입 화면이 함께 부른다. 진입 경로에서만 주입하던 것이 「새로고침하면 권한 요청 안내로
   * 튕긴다」는 결함이었다(2026-09-08).
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
  /**
   * 서버 역할 확보 상태를 갱신한다. 호출자는 `features/auth/sessionBootstrap` 한 곳이다.
   *
   * ⚠ 스토어가 조회를 <b>직접 하지 않는다</b> — 여기서 `features/auth/api` 를 부르면
   *   스토어 → api → HTTP 클라이언트 → 스토어 순환이 생긴다.
   */
  setServerRoleStatus: (status: ServerRoleStatus) => void;
  clear: () => void;
  hydrate: () => void;
}

// JWT payload base64url decode (보안: 무결성 검증은 BE에서 — FE는 표시용 클레임만 추출)
// 원형 해독(UTF-8 복원 포함)은 `lib/jwtPayload` 한 곳이 소유한다 — 관제 저장 형식을 채우는
// `features/auth/controlSession` 도 같은 해독을 쓰므로 두 벌로 두지 않는다.
function decodeJwtPayload(token: string): TokenClaims | null {
  try {
    const raw = decodeJwtPayloadRaw(token);
    if (!raw) return null;

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
    const tokenRole: Role | null = hasRole && isKnownRole(rawRole) ? rawRole : null;

    // [@design ADR-012] [@design INT-013] [@design AC-1103]
    // ★채널 판정 축은 <배포 향>이다 — 프론트에서 그 향에 해당하는 것이 빌드 채널이다.
    //   포털 Host 가 넘기는 인계 토큰에는 channel 클레임이 없고, 그것은 누락이 아니라 계약이다
    //   (INT-013 「계약 경계」 — 저작도구는 상대 시스템에 채널 클레임 추가를 요구하지 않는다).
    //   ⚠ 기본값 전환이 아니라 <강제>다: 토큰에 channel 이 실려 있어도 그 값을 따르지 않는다.
    //     포털 산출물이 관제 채널로 동작할 길을 남기지 않기 위해서다.
    //   ⚠ 이 표식을 지우지 말 것 — 같은 결정의 전파 표식이 이미 네 차례 지워졌다(AC-1103).
    //   ⚠ 판정을 모듈 상수로 굳히지 않는다 — `isPortalEmbedChannel()` 은 매 호출 환경값을 다시
    //     읽는 함수이고, 그래야 시험이 두 향을 모두 검증할 수 있다(`lib/buildChannel`).
    const portalBuild = isPortalEmbedChannel();

    // ★포털 향에서는 상대 시스템의 역할을 우리 인가 축에 쓰지 않는다(INT-013 인계 토큰 구성표).
    //   BE `JwtAuthenticationFilter` 의 PORTAL 가지도 토큰 role 을 버리고 PORTAL_USER 로 고정하므로
    //   두 층이 대칭이다.
    //   ⚠ 여기서 `PORTAL_USER` 로 <채워 넣지> 않는다. 정상 경로의 역할은 `GET /v1/me` 응답을
    //     `setServerRole` 이 주입해 채운다. 화면이 역할을 지어내면 그 조회가 실패했을 때 있지도
    //     않은 권한을 화면이 인정하는 fail-open 이 된다. null 로 두면 RoleGuard 가 「확인하지
    //     못했다」 안내(재시도 제공)로 떨어진다 — 그것이 옳은 동작이다.
    const role: Role | null = portalBuild ? null : tokenRole;

    // 관제 인계 토큰은 channel 클레임을 싣지 않는다 — BE 는 부재 시 INTERNAL 로 기본 처리한다
    //   (@design ADR-063). FE 도 동일하게 <부재는 INTERNAL>로 본다. 부재를 거부하면 관제 토큰이
    //   여기서 탈락해 claims=null → SessionIngress 가 「토큰 없음」으로 오판해 로그인 무한루프가
    //   된다(실측 2026-09-05). 값이 <있는데> 우리가 모르는 채널이면 종전대로 거부(BE valueOf 대칭).
    //   ⚠ 이 근거는 <관제 향에 한정>된다 — 틀렸던 것이 아니라 조건부였다. 포털 향에서는 위 강제가
    //     앞서 토큰의 channel 값을 아예 읽지 않는다.
    const channel = portalBuild
      ? Channel.PORTAL
      : raw.channel === undefined || raw.channel === null
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

export const useAuthStore = create<AuthState>((set, get) => ({
  token: null,
  claims: null,
  isHydrated: false,
  serverRoleStatus: 'idle',
  setToken: (token: string) => {
    const claims = decodeJwtPayload(token);
    if (!claims) return; // 유효하지 않은 토큰은 저장하지 않음
    persistToken(token);
    // ★세션을 세우는 것은 <확보 결과를 지우는 것>이기도 하다 — claims 를 토큰 디코드값으로
    //   재구성하므로 주입해 둔 서버 역할이 함께 사라진다. 그 사실을 상태로 남기지 않으면
    //   가드가 「역할 없음」으로 판정한다(2026-09-08 에 고친 결함).
    set({ token, claims, serverRoleStatus: 'unacquired' });
  },
  setTokenAndClaims: (token: string) => {
    const claims = decodeJwtPayload(token);
    if (!claims) return;
    persistToken(token);
    set({ token, claims, serverRoleStatus: 'unacquired' });
  },
  replaceToken: (token: string) => {
    const claims = decodeJwtPayload(token);
    if (!claims) return false;
    const prev = get();
    persistToken(token);
    if (!prev.claims || prev.claims.sub !== claims.sub) {
      set({ token, claims, serverRoleStatus: 'unacquired' });
      return true;
    }
    set({
      token,
      // 서버가 주입한 역할·이름을 보존한다 — 새 토큰의 클레임은 만료·채널 축만 갱신한다.
      claims: { ...claims, role: prev.claims.role, name: prev.claims.name ?? claims.name },
      serverRoleStatus:
        prev.serverRoleStatus === 'pending' ? 'unacquired' : prev.serverRoleStatus,
    });
    return true;
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
  setServerRoleStatus: (status: ServerRoleStatus) => {
    set({ serverRoleStatus: status });
  },
  clear: () => {
    forgetPersistedToken();
    // 세션이 사라지면 확보 상태도 함께 초기화한다 — 남겨 두면 다음 세션이 앞 세션의
    // 「확인 실패」를 물려받아 멀쩡한 진입에 오류 안내가 뜬다.
    set({ token: null, claims: null, serverRoleStatus: 'idle' });
  },
  hydrate: () => {
    // 포털 채널은 저장소에 보관하지 않으므로 복원할 것이 없다 — 곧바로 hydration 완료로 넘어간다.
    // (Host 가 창구로 토큰을 내주므로 새로고침 복원은 Host 의 몫이다.)
    let stored = readPersistedToken();
    // [@design ADR-012] [@design INT-013]
    // ★ 관제 채널에서는 <b>같은 출처 저장소의 현재값이 우선</b>이다. 이 탭의 복사본(sessionStorage)은
    //   다른 탭(관제 탭 포함)이 갱신한 뒤에는 옛 토큰이라, 그대로 복원하면 새로고침만으로 만료된
    //   토큰에 붙들린다. 단 <b>이 탭에 세션이 있었을 때만</b> 바꿔 끼운다 — 복사본이 없는 탭에서
    //   저장소 토큰으로 세션을 새로 세우면 진입 화면(`/ingress`)을 건너뛰게 된다.
    if (stored && !isPortalEmbedChannel() && isLocalStorageIngressEnabled()) {
      const current = readValidatedLocalStorageToken();
      if (current && current !== stored) {
        stored = current;
        persistToken(current);
      }
    }
    if (stored) {
      const claims = decodeJwtPayload(stored);
      // 만료된 토큰은 무시
      if (claims && claims.exp > Math.floor(Date.now() / 1000)) {
        // 복원된 세션도 <확보 전>이다. 서버 역할은 저장소에 두지 않으므로 새로고침이면
        // 통째로 사라져 있고, 그 사실을 표식으로 남겨야 가드가 판정을 미룬다.
        set({ token: stored, claims, isHydrated: true, serverRoleStatus: 'unacquired' });
        return;
      }
      // 만료됐으면 스토리지에서도 제거
      forgetPersistedToken();
    }
    set({ isHydrated: true });
  },
}));
