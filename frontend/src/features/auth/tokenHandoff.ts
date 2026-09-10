// [@design INT-013]
/**
 * 토큰 인계 창구 — 「access token 을 <어디서> 얻는가」의 단일 지점.
 *
 * ## 왜 어댑터 한 겹이 필요한가 (되돌리기 전에 반드시 읽을 것)
 *
 * 지금까지 저작도구는 토큰을 **브라우저 저장소에서 직접 읽었다**. 관제 채널에서는 그것이
 * 옳다 — 관제와 같은 출처라 저장소가 곧 인계 채널이고, 저장소에 든 값이 언제나 현재 값이다.
 *
 * 포털 채널은 전제가 다르다. 저작도구 프론트는 포털 화면 안에서 **런타임으로 실행되는
 * Remote** 이고, access token 은 **Host 메모리에만** 있다(브라우저 저장소 미사용).
 * 이 상황에서 저장소나 그 스냅샷을 직접 읽으면 이렇게 깨진다:
 *
 *   1. 진입 시점에 토큰을 한 번 읽어 우리 쪽 변수·스토어에 복사해 둔다.
 *   2. Host 가 세션을 갱신한다 — Host 메모리의 토큰은 새 값으로 바뀐다.
 *   3. 우리는 그 사실을 모른다. **이미 무효가 된 옛 토큰을 계속 붙잡고** 요청을 보낸다.
 *   4. 전 API 가 401 로 떨어진다.
 *
 * ★ 이 파손은 **수십 분을 한 화면에 머무는 저작 화면에서 반드시 일어난다.** 라벨링·검수는
 *   한 화면에 오래 앉아 작업하는 흐름이라 「토큰이 갱신될 만큼 오래 머무는」 것이 예외가
 *   아니라 정상이다. 포털이 이것을 **비협상 조건**으로 제시했고 `INT-013` 이 조치를
 *   「저장소 직접 읽기를 토큰 획득 창구 뒤로 추상화한다」로 못박았다. 이 파일이 그 창구다.
 *
 * ⚠ **그래서 포털 채널의 `getAccessToken()` 은 값을 캐시하지 않는다.** 호출할 때마다 Host 에
 *   다시 묻는다. 「매번 묻는 것은 낭비다」라는 이유로 여기에 메모이제이션·모듈 상수·스토어
 *   복사를 끼워 넣으면 위 1~4 가 그대로 되살아난다. 그 결함은 **갱신이 일어난 뒤에만**
 *   드러나므로 개발 중에는 조용하다.
 *
 * ⚠ 같은 이유로 포털 채널에서 Host 창구가 없을 때 **스토어로 폴백하지 않는다.** 폴백하면
 *   「창구가 아직 주입되지 않았다」가 「옛 토큰으로 조용히 돌아간다」가 되어, 막으려던 결함이
 *   장애 상황에서만 되살아나는 최악의 형태가 된다. 없으면 없는 것이다(fail-closed).
 *
 * ## 계약 이름은 우리가 정한 것이 아니다
 * 창구 네 개(`getAccessToken` · `refresh` · `onUnauthorized` · `notifyActivity`)의 이름은
 * **포털이 정본으로 제시한 계약**이고 Host 가 주입하는 객체의 **키**다. 개념만 맞추고 이름을
 * 새로 지으면 **실행 시점에** 창구를 찾지 못한다 — 빌드는 통과하고 타입 오류도 없다.
 * 그래서 이름은 `HOST_HANDOFF_METHOD_NAMES` 로 값 고정하고 회귀 가드가 값 축으로 잡는다.
 *
 * ## 범위
 * - 토큰 **조달 경로**를 창구 뒤로 옮긴다(슬라이스 3a).
 * - **요청 헤더도 채널별로 갈린다**(2026-09-05 완료 — 아래 `buildAuthHeader`).
 *   ⚠ 이 자리에는 *"헤더는 `Authorization: Bearer` 그대로다 — 전용 헤더로 바꾸는 것은 백엔드가
 *   그 헤더를 inbound 로 수용한 뒤"* 라는 대기 사유가 적혀 있었다. **그 조건은 해소됐다** —
 *   백엔드 `common/security/JwtAuthenticationFilter` 가 `x-access-token` 을 inbound 로 수용한다
 *   (`CO-20260905-포털-전용인증헤더-inbound-수용`). 그러므로 「백엔드가 아직 안 받는다」를
 *   근거로 이 전환을 되돌리지 말 것.
 * - `refresh` · `onUnauthorized` · `notifyActivity` 는 **창구만 열어 둔다.** 호출 지점 배선은
 *   후속이다. 특히 401 경로는 이번에 건드리지 않는다 — 근거는 아래 `onUnauthorized` 주석.
 *
 * 회귀 가드: `features/auth/__tests__/tokenHandoff.test.ts` ·
 *            `lib/api/__tests__/clientTokenHandoff.test.ts`
 */
import { isPortalEmbedChannel } from '@/lib/buildChannel';
import { useAuthStore } from '@/stores/useAuthStore';

/**
 * Host 가 주입하는 인계 창구의 모양.
 *
 * ⚠ 필드 이름을 바꾸지 말 것 — 이것은 우리 내부 타입이 아니라 **외부와 합의한 계약**이다.
 */
export interface TokenHandoffGateway {
  /**
   * 현재 유효한 access token 을 돌려준다. 없으면 `null`.
   *
   * ★ **반환 형태가 동기·비동기 «둘 다»인 것은 의도다.** 이 창구는 우리가 구현하는 것
   *   (내부 채널·개발 대역)과 **Host 가 구현해 주입하는 것**이 섞이는 자리다.
   *   - 내부(관제) 채널은 스토어를 읽으므로 **동기**가 정직하다.
   *   - 포털 Host 는 **`Promise`** 를 준다. 갱신이 오가는 «동안» 옛 토큰을 넘기지 않으려면
   *     갱신 큐에 붙어 기다려야 하고, 동기 반환은 정확히 그 죽은 토큰을 넘기기 때문이다
   *     (포털 인계문서 2026-09-08 §3-1).
   *
   * ⚠ **한쪽으로 좁히지 말 것.** 동기로 좁히면 Host 가 준 `Promise` 객체가 그대로 토큰
   *   자리에 들어가 `token.split('.')` 에서 터진다 — 실측으로 확인된 형태이며, 그때
   *   Host 화면에는 「저작도구를 불러오지 못했습니다」라는 **원인과 무관한 문구**가 뜬다.
   *   비동기로 좁히면 내부 채널이 없는 비동기를 흉내내야 한다.
   *   읽는 쪽은 아래 {@link getAccessToken} 하나뿐이고 그쪽이 `await` 로 둘을 흡수한다.
   */
  getAccessToken(): string | null | Promise<string | null>;
  /** 만료 임박·만료 시 재발급을 요청한다. 갱신된 토큰 또는 `null`. */
  refresh(): Promise<string | null>;
  /** 인증이 끊겼음을 Host 에 알린다 — 안내·재로그인은 Host 가 소유한다. */
  onUnauthorized(): void;
  /** 사용자 활동을 Host 에 알려 세션 유지 판단에 쓰게 한다. */
  notifyActivity(): void;
}

/**
 * 계약 이름 4종의 **값** 고정.
 *
 * 형식 검사(「함수가 네 개 있다」)는 이름이 바뀌어도 통과한다. 이름이 계약이므로 값으로
 * 못박아야 회귀 가드가 실제로 무언가를 지킨다.
 */
export const HOST_HANDOFF_METHOD_NAMES = [
  'getAccessToken',
  'refresh',
  'onUnauthorized',
  'notifyActivity',
] as const;

/**
 * 내부(관제) 채널 창구 — **지금 동작 그대로**다.
 *
 * 관제는 같은 출처의 브라우저 저장소로 토큰을 인계하고, 저작도구는 그것을 인계받아
 * `useAuthStore`(메모리 + sessionStorage)에 둔다. 저장소가 곧 현재 값이라 위에서 말한
 * 「죽은 토큰」 문제가 성립하지 않는다 — 그러므로 여기서는 스토어를 읽는 것이 옳다.
 */
export const internalTokenHandoff: TokenHandoffGateway = {
  getAccessToken() {
    return useAuthStore.getState().token;
  },

  /**
   * 내부 채널에는 **갱신 창구가 없다.** 관제가 저장소에 새 토큰을 넣는 것이 곧 갱신이고
   * 저작도구가 재발급을 요청할 상대가 없다. 현재 값을 그대로 돌려준다 —
   * 없는 능력을 있는 척하지 않되, 호출부가 채널을 분기하지 않아도 되게 한다.
   */
  async refresh() {
    return useAuthStore.getState().token;
  },

  /**
   * 내부 채널의 인증 끊김 처리는 **이미 있다** — 요청 인터셉터가 토큰을 지우고 상위 시스템
   * 로그인 화면으로 보낸다. 그 자리가 실재하므로 그대로 두는 것이 맞다(`INT-013`).
   * 여기서 같은 일을 또 하면 이동이 두 번 걸린다.
   */
  onUnauthorized() {
    /* no-op — 내부 채널의 401 처리는 요청 인터셉터가 소유한다 */
  },

  /**
   * 내부 채널에는 활동을 알릴 Host 가 없다. 세션 수명은 상위 시스템이 발급한 토큰의
   * 만료 시각이 정하며 저작도구가 연장하지 못한다.
   */
  notifyActivity() {
    /* no-op — 알릴 상대가 없다 */
  },
};

/**
 * Host 가 주입한 창구. 포털 채널에서만 채워진다.
 *
 * 모듈 스코프 가변 값인 이유: Remote 진입점이 Host 에게서 받아 **실행 시점에** 넘겨주기
 * 때문이다. 빌드타임에 알 수 있는 값이 아니다.
 */
let hostGateway: TokenHandoffGateway | null = null;

/**
 * 직전에 등록을 시도한 후보 — **같은 값으로 다시 부르는 것을 걸러내기 위한 것**이다.
 *
 * `null`·`undefined` 도 정당한 후보값이라 「아직 시도한 적 없음」과 구분되지 않는다.
 * 그래서 그 둘과 절대 같지 않은 표식을 초깃값으로 둔다.
 */
const NOT_TRIED = Symbol('not-tried');
let lastCandidate: unknown = NOT_TRIED;

function isGateway(value: unknown): value is TokenHandoffGateway {
  if (typeof value !== 'object' || value === null) return false;
  const v = value as Record<string, unknown>;
  return HOST_HANDOFF_METHOD_NAMES.every((name) => typeof v[name] === 'function');
}

/**
 * Host 가 주입한 인계 창구를 등록한다 — Remote 진입점이 부른다.
 *
 * ★ **모양을 여기서 검사하고 어긋나면 거부한다.** 이름이 하나라도 다르면 그대로 받아 두었다가
 *   첫 요청에서 `undefined is not a function` 으로 터지는데, 그때는 원인이 「토큰이 안 붙는다」로
 *   보여 인증·권한 쪽을 뒤지게 된다. 받는 자리에서 거부하면 원인이 등록 시점에 드러난다.
 *
 * @returns 등록 성공 여부. 실패면 창구는 비어 있는 상태로 남는다(fail-closed).
 */
export function registerHostTokenHandoff(gateway: unknown): boolean {
  // ★ 같은 후보면 즉시 끝낸다 — 이 함수는 Remote 진입점이 **렌더마다** 부른다.
  //   등록을 효과로 미루면 첫 라우트 가드가 토큰 없이 판정해 인증 안내가 한 프레임 비치고,
  //   효과에 두면 개발 빌드의 재마운트(mount→unmount→mount)에서 정리가 등록을 앞질러
  //   창구가 빈 채로 남는다. 멱등으로 만들어 렌더에서 부르는 쪽이 둘 다 없다.
  if (lastCandidate !== NOT_TRIED && gateway === lastCandidate) return hostGateway !== null;
  lastCandidate = gateway;

  if (!isGateway(gateway)) {
    // 토큰 값 자체는 절대 싣지 않는다 — 어떤 키가 왔는지만 남긴다.
    console.error(
      '[tokenHandoff] Host 인계 창구의 모양이 계약과 다르다. 필요한 키: ' +
        HOST_HANDOFF_METHOD_NAMES.join(', '),
    );
    return false;
  }
  hostGateway = gateway;
  return true;
}

/** 등록된 Host 창구를 비운다 — 언마운트·테스트 정리용. */
export function clearHostTokenHandoff(): void {
  hostGateway = null;
  lastCandidate = NOT_TRIED;
}

/**
 * 포털 채널 창구 — 언제나 Host 에 **다시 묻는다**.
 *
 * 값을 들고 있지 않는 것이 이 객체의 전부이자 존재 이유다. 파일 상단의 1~4 를 참조.
 */
export const portalTokenHandoff: TokenHandoffGateway = {
  async getAccessToken() {
    if (!hostGateway) return null;
    // `await` 는 Promise 가 아닌 값에도 안전하다 — Host 가 동기로 돌려주든 Promise 로
    // 돌려주든 여기서 같은 모양이 된다. 그것이 위 인터페이스가 union 인 이유다.
    return (await hostGateway.getAccessToken()) ?? null;
  },
  async refresh() {
    return (await hostGateway?.refresh()) ?? null;
  },
  onUnauthorized() {
    hostGateway?.onUnauthorized();
  },
  notifyActivity() {
    hostGateway?.notifyActivity();
  },
};

/**
 * 이 산출물이 써야 할 인계 창구.
 *
 * 채널 판정은 `isPortalEmbedChannel()` 을 **재사용**한다. `import.meta.env` 를 여기서 다시
 * 읽으면 채널 판정이 두 벌이 되어(오타 처리·기본값 정책 포함) 한쪽만 갱신될 때 조용히 갈린다
 * (`lib/remoteMount.resolveRouterBasename` 과 같은 관례).
 */
export function resolveTokenHandoff(): TokenHandoffGateway {
  return isPortalEmbedChannel() ? portalTokenHandoff : internalTokenHandoff;
}

/**
 * 현재 채널의 access token — **호출부가 채널을 분기하지 않는다.**
 *
 * 요청 인터셉터는 이 함수만 부른다. 「관제면 스토어, 포털이면 Host」 같은 판단이 호출부에
 * 흩어지면 새 호출부가 생길 때마다 그 판단이 복제되고, 한 곳만 빠뜨려도 그 경로에서
 * 죽은 토큰이 나간다.
 */
export async function getAccessToken(): Promise<string | null> {
  try {
    return normalizeToken(await resolveTokenHandoff().getAccessToken());
  } catch (e) {
    // ★ 창구는 **외부(Host)가 구현하는 자리**다. 그쪽이 던지면 우리 부팅·요청이 통째로
    //   멈추는데, 그건 「토큰이 없다」보다 나쁜 결말이다. 없는 것으로 보고 계속 간다.
    //   ⚠ 토큰 값은 절대 싣지 않는다 — 예외의 이름만 남긴다.
    console.error(
      `[tokenHandoff] 인계 창구 호출이 예외로 끝났다(${e instanceof Error ? e.name : typeof e}). ` +
        '토큰 없음으로 처리한다.',
    );
    return null;
  }
}

/**
 * 창구가 돌려준 값을 **토큰으로 쓸 수 있는 형태인지** 확인한다 — 아니면 `null`(fail-closed).
 *
 * ★ 이 검사가 있는 이유는 방어가 아니라 **진단**이다. 창구는 외부(Host)가 구현하는 자리라
 *   우리가 모양을 강제할 수 없고, 어긋난 값이 그대로 흘러가면 훨씬 뒤에서 엉뚱한 모습으로
 *   터진다. 실측: 문자열 대신 `Promise` 가 들어오자 JWT 해독의 `token.split('.')` 이
 *   `e.split is not a function` 으로 죽었고, Host 화면에는 「저작도구를 불러오지
 *   못했습니다」가 떴다 — **토큰 문제라는 사실이 어디에도 남지 않았다.**
 *
 * ⚠ 값 자체는 절대 로그에 싣지 않는다. 어떤 «형»이 왔는지만 남긴다.
 */
function normalizeToken(value: unknown): string | null {
  if (value === null || value === undefined) return null;
  if (typeof value === 'string') return value.trim() === '' ? null : value;
  console.error(
    `[tokenHandoff] 인계 창구가 문자열이 아닌 값을 돌려주었다(형: ${typeof value}). ` +
      '토큰 없음으로 처리한다.',
  );
  return null;
}

/* ------------------------------------------------------------------------- *
 * 「토큰을 어느 헤더에 어떤 모양으로 싣는가」 — 조달 경로와 짝을 이루는 축
 * ------------------------------------------------------------------------- */

/**
 * 포털 채널 전용 인증 헤더 이름 — **포털이 정한 계약값**이다(`INT-013` `auth_detail.header_name`).
 *
 * ⚠ 우리가 고를 수 있는 이름이 아니다. 바꾸면 백엔드가 토큰을 찾지 못해 포털 채널 전 API 가
 *   401 이 되고, 그 실패는 빌드가 아니라 **런타임에** 드러난다.
 */
export const PORTAL_ACCESS_TOKEN_HEADER = 'x-access-token';

/** 내부(관제) 채널 인증 헤더 이름 — 기존 동작 그대로. */
export const INTERNAL_AUTH_HEADER = 'Authorization';

/** 내부 채널이 쓰는 스킴 접두. 포털 채널에는 **붙이지 않는다**(아래 참조). */
export const BEARER_PREFIX = 'Bearer ';

/** 요청에 실을 인증 헤더 한 벌. */
export interface AuthRequestHeader {
  name: string;
  value: string;
}

/**
 * 현재 채널이 토큰을 싣는 **헤더 이름**.
 *
 * 값 조립 없이 이름만 필요한 자리(이미 실려 나간 헤더를 되읽는 401 재시도 판정)를 위해 분리해
 * 둔다. 이름을 그쪽에서 따로 적으면 채널 전환 시 한쪽만 갱신돼 **재시도가 영영 발동하지 않는**
 * 형태로 조용히 어긋난다.
 */
export function resolveAuthHeaderName(): string {
  return isPortalEmbedChannel() ? PORTAL_ACCESS_TOKEN_HEADER : INTERNAL_AUTH_HEADER;
}

/**
 * 토큰을 현재 채널의 계약대로 헤더 한 벌로 만든다 — **요청에 인증을 싣는 단일 지점**.
 *
 * ## 포털 채널에 `Bearer` 접두를 붙이지 않는 이유 (실측으로 고정된 서버 동작)
 *
 * `INT-013` 이 **Bearer 스킴 미사용**을 못박았고(`auth_type = "other"`), 백엔드가 그것을
 * **거부로 강제**한다. 그러므로 `Authorization` 헤더 값을 그대로 복사해 넣는 식의 구현은
 * **전량 401** 이 된다. 「일관성」을 이유로 두 채널의 값 모양을 맞추지 말 것.
 *
 * ⚠ **거부되는 것은 같은데 사유가 둘이며, 둘을 섞으면 잘못된 역추론이 나온다.**
 *
 * | 보낸 모양 | 서버가 하는 일 |
 * |---|---|
 * | 전용 헤더 **단독** + `Bearer ` 접두 | 그 문자열 **전체가 토큰**이 되어 **서명 파싱에서 거부**(401) |
 * | 전용 헤더 + `Authorization` **함께** | 두 값을 비교해 **값 충돌**로 거부(401) |
 *
 * 즉 「헤더를 하나만 보내니 접두가 붙어도 괜찮다」는 **틀렸다** — 그때도 401 이고 사유만 다르다.
 * 근거: 백엔드 `JwtFilterPortalHeaderIngressTest`
 * (`전용헤더는_Bearer_접두를_요구하지_않는다 — 접두를_붙이면_오히려_거부된다` · 두 헤더 충돌 케이스).
 *
 * ## 한 요청에 헤더를 **하나만** 싣는다
 *
 * 백엔드는 두 헤더가 함께 오고 **값이 다르면 401**(fail-closed) 로 거부한다. 채널마다 하나만
 * 실으면 그 **충돌** 판정에 닿을 일이 없다 — 그래서 이 함수는 `{name, value}` 를 **한 벌만**
 * 돌려준다. 반대 채널 헤더를 「호환을 위해」 함께 붙이려는 시도가 곧 그 401 이다.
 * ⚠ 다만 이것이 위 접두 금지를 대신하지는 않는다 — 한 벌만 보내도 접두가 붙으면 파싱에서 죽는다.
 */
export function buildAuthHeader(token: string): AuthRequestHeader {
  return isPortalEmbedChannel()
    ? { name: PORTAL_ACCESS_TOKEN_HEADER, value: token }
    : { name: INTERNAL_AUTH_HEADER, value: `${BEARER_PREFIX}${token}` };
}
