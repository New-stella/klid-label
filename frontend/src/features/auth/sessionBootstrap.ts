// [@design ADR-063] [@design UC-041] [@design SEQ-034] [@design SCREEN-001] [@design API-006]
/**
 * 세션 부트스트랩 — <b>토큰 확보 → 서버 인가 역할 확보</b>의 단일 지점.
 *
 * <h2>왜 이 파일이 생겼나 (되돌리기 전에 반드시 읽을 것)</h2>
 *
 * 인가의 진실원은 <b>저작도구가 보관한 역할</b>({@code GET /v1/me})이지 인계 토큰의 역할
 * 클레임이 아니다({@code ADR-063}). 그 값은 {@code useAuthStore.setServerRole} 로
 * <b>메모리(store)에만</b> 주입된다 — 브라우저 저장소에 두면 서버에서 역할이 바뀌거나
 * 회수돼도 옛 값이 계속 읽히는 <b>stale 권한</b>이 되기 때문이다.
 *
 * 문제는 <b>그 값의 수명</b>이었다. 주입은 진입 화면(`/ingress`)에서 <b>한 번만</b> 일어났고,
 * <b>새로고침은 그 화면을 다시 지나지 않는다</b> — 앱은 저장소의 토큰을 복원할 뿐이다. 그래서
 * 화면을 다시 불러올 때마다 서버 역할이 통째로 사라지고, 관제 인계 토큰처럼 우리 역할 값이
 * 실려 오지 않는 세션은 <b>매번 「역할 없음」으로 판정</b>돼 권한 요청 안내로 튕겼다.
 *
 * ⇒ 조치는 <b>「진실원을 토큰으로 낮추는 것」이 아니라 「복원 경로에서 다시 묻는 것」</b>이다.
 *   화면을 다시 불러올 때마다 다시 묻고, 그 조회가 끝나기 전에는 도착지를 정하지 않는다.
 *
 * <h2>세 가지 불변</h2>
 * <ol>
 *   <li><b>채널을 가리지 않는다.</b> 예전에는 이 주입이 관제 진입 경로 <b>한 곳</b>에만 있어
 *       포털 채널은 최초 진입에도 새로고침에도 서버 역할을 <b>한 번도 묻지 않았다</b>. 포털
 *       토큰에 역할이 늘 실려 튕기지 않았을 뿐, 서버가 역할을 바꾸거나 회수해도 화면은 옛
 *       역할로 계속 움직였다 — 시끄럽게 깨지지 않았을 뿐 같은 결함이다.
 *       ⚠ <b>조회 시점을 채널마다 다르게 만들지 말 것</b> — 두 벌이 되면 한쪽만 낡는다.</li>
 *   <li><b>저장소에 보관하지 않는다.</b> 확보한 역할은 화면 수명 동안만 산다.</li>
 *   <li><b>확인하지 못한 것을 「없음」으로 다루지 않는다.</b> 조회 실패는 실패로 드러낸다
 *       (아래 {@link ServerRoleOutcome}).</li>
 *   <li>★<b>확보 결과의 재사용은 세션에 종속된다</b>(2026-09-08 · {@code ADR-063} ⑥~⑧).
 *       「같은 인계 토큰이면 다시 묻지 않는다」만으로는 부족하다 — 세션이 끊겼다
 *       <b>같은 토큰으로 다시 서는</b> 경우가 실재하고 그때 역할은 상태에 앉아 있지 않다.
 *       그래서 ①세션이 갈리면 캐시를 버리고 ②캐시가 히트해도 결과를 <b>지금 세션에 다시
 *       앉히며</b> ③응답은 <b>그것을 요청한 세션에만</b> 반영하고 ④확보 결과를 <b>그 시도
 *       안에</b> 둔다(독립 슬롯에 두면 갈린 옛 세션의 늦은 응답이 그것을 덮는다).</li>
 * </ol>
 *
 * 회귀 가드: `__tests__/AppSessionBootstrap.test.tsx` ·
 *            `features/auth/__tests__/sessionRefreshRoleRestore.test.tsx` ·
 *            `features/auth/__tests__/sessionSwapRoleReacquire.test.tsx`(세션 교체 축)
 */
import { ApiError } from '@/lib/api/errors';
import type { Role } from '@/lib/api/types';
import { useAuthStore } from '@/stores/useAuthStore';

import { getMe } from './api';

/**
 * 확보 시도의 결말. <b>네 갈래를 하나로 합치지 말 것</b> — 호출자가 해야 할 일이 각각 다르다.
 *
 * - `resolved`     서버가 답했다. `role === null` 은 <b>확인해서 알아낸 「역할 없음」</b>이며
 *                  온보딩(최초 관리자 등록) 대상이다.
 * - `unauthorized` 401 — 인증이 유효하지 않다. 역할 없음이 <b>아니다</b>. 재로그인 축이다.
 * - `failed`       그 밖의 실패 — 역할을 <b>확인하지 못했다</b>.
 * - `no-session`   물어볼 토큰이 없다. 실패가 아니라 <b>아직 해당 없음</b>이다.
 */
export type ServerRoleOutcome =
  | { kind: 'resolved'; role: Role | null }
  | { kind: 'unauthorized' }
  | { kind: 'failed' }
  | { kind: 'no-session' };

/**
 * 「역할을 확인하지 못했다」의 안내 문구 — <b>진입 화면과 라우트 가드가 함께 쓴다.</b>
 *
 * 두 곳에 각각 적으면 한쪽만 고쳐져, 같은 사유가 화면마다 다른 말을 하게 된다.
 * ⚠ 이 문구를 「역할이 없습니다」로 바꾸지 말 것 — 그 순간 고친 결함이 되살아난다.
 */
export const SERVER_ROLE_UNKNOWN_TITLE = '사용자 정보를 확인할 수 없습니다';
export const SERVER_ROLE_UNKNOWN_DESC =
  '잠시 후 다시 시도해주세요. 문제가 계속되면 관리자에게 문의해주세요.';

/**
 * 확보가 끝난 뒤 <b>세션에 앉힐 값</b>. 결과를 promise 안에만 두면 「두 번째 호출」이 앞의
 * promise 를 돌려줄 뿐 <b>스토어에는 아무것도 쓰지 않는다</b>. 그 사이에 `setToken` 이 claims 를
 * 토큰 디코드값으로 재구성해 두었으면 주입해 둔 서버 역할이 사라진 채로 남는다.
 */
interface AcquiredRole {
  role: Role | null;
  name?: string | null;
  status: 'ready' | 'failed';
}

/**
 * 진행 중이거나 이미 끝난 확보 시도. <b>토큰으로 키를 잡는다.</b>
 *
 * ★ 끝난 뒤에도 비우지 않는다 — 그래야 같은 토큰에 대한 두 번째 호출이 <b>요청을 다시 쏘지
 *   않는다</b>. 새로고침 복원(앱 부팅)과 진입 화면(`/ingress`)이 같은 순간에 둘 다 부르는
 *   경로가 실재하며, 비우면 그 자리에서 `/me` 가 두 번 나간다.
 * ★ 실패도 캐시한다 — 실패마다 재시도하면 화면 전환마다 요청이 쌓인다. 재시도는 사용자가
 *   <b>다시 시도를 누르거나</b>(→ {@link retryServerRole}) 화면을 다시 불러올 때 일어난다.
 * ★★ <b>세션이 갈리면 함께 버린다</b> — 아래 스토어 구독이 그 일을 한다.
 */
interface RoleAttempt {
  readonly token: string;
  readonly promise: Promise<ServerRoleOutcome>;
  /**
   * ★★<b>결과는 이 시도 안에 산다</b>(2026-09-08 · {@code ADR-063} ⑥~⑧).
   *
   * 예전에는 결과가 <b>모듈 스코프의 독립 슬롯</b>이었고 「토큰이 같은가」로만 자기 것인지를
   * 판정했다. 그래서 갈린 <b>옛 세션의 늦은 응답</b>이 그 슬롯을 덮었고, 그 뒤 지금 세션으로
   * 확보를 다시 부르면 <b>캐시는 히트하는데 결과는 남의 것</b>이라 아래 캐시 히트 분기가
   * 확보 상태를 `'pending'` 으로 되돌려 <b>영구 고착</b>했다(재시도 조작은 `'failed'` 에만
   * 있어 탈출구도 없었다 — 401 이나 문서 재적재뿐).
   *
   * ⇒ 결과 칸을 시도와 <b>한 몸</b>으로 묶으면 다른 세션의 결과가 <b>구조적으로 끼어들 수
   *   없다</b>. 토큰 비교로 걸러 내는 것이 아니라 애초에 닿지 못한다.
   *
   * `value === null` 은 <b>아직 돌고 있다</b>는 뜻이다. 확보 절차는 성공·실패 <b>양쪽</b>에서
   * 세션에 앉히기 <b>전에</b> 이 칸을 채우므로, 「promise 는 끝났는데 칸이 비어 있는」 상태는
   * 나오지 않는다.
   */
  readonly acquired: { value: AcquiredRole | null };
}

let attempt: RoleAttempt | null = null;

/** 확보 이력을 통째로 버린다. 세션이 갈렸거나 사람이 재시도를 눌렀을 때. */
function discardAttempt(): void {
  attempt = null;
}

// [@design ADR-063] [@design AC-1016]
/**
 * ★<b>세션이 갈리면 확보 캐시도 함께 갈린다.</b>
 *
 * 캐시는 <b>모듈 스코프</b>라 문서가 다시 뜨지 않는 한 살아남는다. 그런데 세션이 갈리는 경로
 * (401 → `clear()` → 진입 화면이 <b>같은 토큰으로</b> 다시 `setToken()`)는 <b>같은 화면 수명
 * 안</b>에서 일어난다. 무효화하지 않으면 캐시가 히트하면서 스토어에는 아무것도 재적용되지 않아
 * 역할 보유자가 「역할 없음」으로 판정된다.
 *
 * ⚠ <b>방향을 뒤집어 둔 것이 핵심이다.</b> 스토어가 이 모듈을 import 하면
 *   스토어 → 부트스트랩 → 스토어 순환이 생긴다. 그래서 <b>스토어가 알리고(구독) 부트스트랩이
 *   듣는다</b>. 무효화 지점을 `clear`·`setToken`·`setTokenAndClaims` 에 각각 심으면 새 지점이
 *   생길 때마다 빠뜨릴 수 있는데, 토큰 변화 하나로 판정하면 그 부류가 통째로 닫힌다.
 *
 * ⚠ 여기서 곧바로 `ensureServerRole()` 을 부르지 않고 <b>미확보 표식이 설 때만</b> 부른다.
 *   토큰 변화 전부에 붙이면 스토어에 상태를 직접 심는 시험(151개 파일)이 모두 조회를 쏘게 된다.
 *   `'unacquired'` 는 <b>실제 세션 수립 경로에서만</b> 서므로 그 폭발이 없다.
 */
const unsubscribeFromSession = useAuthStore.subscribe((state, prev) => {
  if (state.token !== prev.token) discardAttempt();
  if (state.serverRoleStatus === 'unacquired' && prev.serverRoleStatus !== 'unacquired') {
    // ★<b>안전망</b>이다. 진입 화면·앱 부팅은 스스로 부르지만, 권한 자가 부여 직후처럼
    //   토큰만 갈아끼우는 경로가 부르지 않으면 가드가 미확보에 <b>영구히 갇힌다</b>.
    //   ⚠ 자동 <b>반복</b>이 아니다 — 세션이 새로 설 때 한 번이다(`ADR-063` ⑨).
    //   ⚠ 마이크로태스크로 미룬다 — `set()` 안에서 다시 `set()` 하면 다른 구독자가 보는
    //     상태·이전 상태 쌍이 어긋난다. 미확보가 이미 판정을 막고 있어 깜빡임은 없다.
    queueMicrotask(() => {
      void ensureServerRole();
    });
  }
});

// 개발 전용 정리. 운영 번들에는 이 블록 자체가 남지 않는다(`import.meta.hot` 은 개발 서버에서만
// 정의된다). 모듈이 다시 실행될 때 앞의 구독을 끊지 않으면 구독이 <b>누적</b>되어, 세션이 설
// 때마다 확보가 중복 개시된다 — 캐시가 중복 <b>요청</b>은 막지만 스토어 쓰기와 로그는 겹친다.
import.meta.hot?.dispose(() => {
  unsubscribeFromSession();
});

/**
 * 확보 결과를 <b>지금 세션에</b> 앉힌다.
 *
 * [@design ADR-063] [@design AC-1017]
 * ★<b>응답은 그것을 요청한 세션에만 반영한다.</b> 조회가 도는 사이에 세션이 갈릴 수 있고
 * (401 인터셉터의 `clear()` 가 대표적이다), 그때 결과를 그냥 쓰면 <b>세션 없는 상태에
 * 「확인 실패」가 남아</b> 다음 진입이 근거 없이 오류 안내로 떨어진다. 성공도 같다 — 옛 세션의
 * 역할이 새 세션에 주입되면 대기 게이트가 잘못된 값으로 풀린다.
 */
function applyToSession(sessionToken: string, acquired: AcquiredRole): void {
  const store = useAuthStore.getState();
  if (store.token !== sessionToken) return;
  if (acquired.status === 'ready') {
    // ★역할과 이름을 <b>함께</b> 넘긴다 (@design SHELL-001). 역할만 취하고 이름을 버리면 관제
    //   인계 토큰처럼 이름 클레임이 없는 세션에서 헤더가 대체 표기(「사용자」)에 고착된다.
    //   빈 이름은 스토어가 무시하므로 토큰 클레임이라는 보조 조달원이 살아 있다.
    store.setServerRole(acquired.role, acquired.name);
  }
  store.setServerRoleStatus(acquired.status);
}

/**
 * 서버 인가 역할을 확보한다. <b>이미 확보했거나 확보 중이면 다시 묻지 않는다.</b>
 *
 * ★ 다시 묻지 않을 뿐 <b>결과는 다시 앉힌다</b>. 「같은 토큰이면 다시 묻지 않는다」만으로는
 *   부족하다 — 세션이 끊겼다 같은 토큰으로 다시 서는 경우가 실재하고, 그때 역할은 상태에
 *   앉아 있지 않다({@code ADR-063} ⑥).
 *
 * ⚠ `async` 함수로 만들지 말 것. 요청 시작 표식(`serverRoleStatus = 'pending'`)은 <b>첫
 *   await 이전에 동기로</b> 세워져야 한다 — 그래야 토큰 복원과 같은 렌더 배치에 묶여
 *   「복원은 끝났는데 아직 pending 이 아닌」 한 프레임이 생기지 않는다. 그 한 프레임이
 *   바로 사용자가 본 <b>권한 요청 안내가 스쳤다 사라지는</b> 깜빡임이다.
 */
export function ensureServerRole(): Promise<ServerRoleOutcome> {
  const { token, claims } = useAuthStore.getState();
  // 토큰이 없으면 물을 것이 없다. 상태를 `pending` 으로 올리지 않는다 — 올리면 로그인하지
  // 않은 사용자가 진입 안내 대신 영구 스피너를 본다.
  if (!token || !claims) return Promise.resolve({ kind: 'no-session' });

  const cached = attempt;
  if (cached !== null && cached.token === token) {
    // 이미 끝났으면 <동기로> 다시 앉힌다 — 가드는 렌더 시점에 동기로 판정하는 자리다.
    // ★읽는 곳은 <b>이 시도 자신의</b> 결과 칸이다. 토큰이 같은지를 다시 묻지 않는다 —
    //   그 비교가 남의 결과를 걸러 내던 자리였고, 걸러 내야 할 것이 애초에 들어오지 않는다.
    const settled = cached.acquired.value;
    if (settled !== null) applyToSession(token, settled);
    else useAuthStore.getState().setServerRoleStatus('pending');
    return cached.promise.then((outcome) => {
      // 아직 돌고 있었다면 <끝난 뒤에> 앉힌다.
      const done = cached.acquired.value;
      if (done !== null) applyToSession(token, done);
      return outcome;
    });
  }

  useAuthStore.getState().setServerRoleStatus('pending');
  // ★결과 칸을 <b>먼저</b> 만들어 확보 절차에 넘긴다. 그래야 응답이 아무리 빨리(또는 늦게)
  //   와도 <b>자기 시도의 칸</b>에만 쓴다. 칸을 나중에 붙이면 그 사이에 끝난 응답이 갈 곳을
  //   잃거나 남의 칸을 찾아간다.
  const acquired: RoleAttempt['acquired'] = { value: null };
  const promise = resolveServerRole(token, acquired);
  attempt = { token, promise, acquired };
  return promise;
}

async function resolveServerRole(
  sessionToken: string,
  acquired: RoleAttempt['acquired'],
): Promise<ServerRoleOutcome> {
  try {
    const me = await getMe();
    acquired.value = { role: me.role, name: me.name, status: 'ready' };
    applyToSession(sessionToken, acquired.value);
    return { kind: 'resolved', role: me.role };
  } catch (err) {
    acquired.value = { role: null, status: 'failed' };
    applyToSession(sessionToken, acquired.value);
    if (err instanceof ApiError && err.status === 401) return { kind: 'unauthorized' };
    return { kind: 'failed' };
  }
}

// [@design ADR-063] [@design AC-1017] [@design SCREEN-001] [@design API-006]
/**
 * <b>사람이</b> 다시 시도한다. 확인 실패 안내(`router/guards` 의 제자리 오류)가 부른다.
 *
 * 실패는 캐시되므로 그대로 두면 <b>문서를 새로 불러올 때까지 영구히 실패</b>다. 화면은
 * "잠시 후 다시 시도해주세요"라고 적으면서 할 수 있는 시도를 주지 않는 상태였다.
 *
 * ⚠ <b>자동 반복으로 만들지 말 것</b>({@code ADR-063} ⑨) — 장애 구간에 조회 폭주가 된다.
 *   호출자는 사람의 조작 하나뿐이다.
 */
export function retryServerRole(): Promise<ServerRoleOutcome> {
  discardAttempt();
  return ensureServerRole();
}

/**
 * 앱 부팅 1회 — 토큰을 복원하고 <b>곧바로</b> 서버 역할 확보를 시작한다.
 *
 * ★ 두 호출이 <b>같은 동기 블록</b>에 있어야 한다. React 18 의 자동 배칭이 두 상태 갱신을 한
 *   번의 렌더로 묶어 주므로, 라우터가 「복원 완료 + 확보 미시작」 상태로 렌더되는 순간이
 *   존재하지 않는다. 사이에 `await` 을 끼워 넣으면 그 창이 열리고 깜빡임이 되살아난다.
 *
 * ⚠ 조회 결과를 여기서 기다리지 않는다 — 기다리면 <b>첫 화면 표시 전체</b>가 그 응답만큼
 *   늦어진다. 기다리는 것은 역할을 판정하는 자리(`router/guards` 의 `RoleGuard`)뿐이다.
 */
export function restoreSession(): void {
  useAuthStore.getState().hydrate();
  void ensureServerRole();
}

/**
 * 시험 전용 — 모듈 스코프 확보 이력과 스토어 상태를 초기화한다.
 *
 * 이 캐시는 <b>토큰으로 키를 잡으므로</b> 시험이 남긴 이력이 다음 시험의 같은 토큰을 조용히
 * 통과시킬 수 있다. 전역 시험 준비(`src/test/setup.ts`)가 매 시험 앞에서 부른다.
 */
export function resetServerRoleResolution(): void {
  discardAttempt();
  useAuthStore.getState().setServerRoleStatus('idle');
}
