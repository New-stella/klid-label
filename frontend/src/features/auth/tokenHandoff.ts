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
 * ## 이번 범위 (슬라이스 3a)
 * - 토큰 **조달 경로**만 창구 뒤로 옮긴다. 요청 헤더는 `Authorization: Bearer` 그대로다 —
 *   포털 계약의 전용 헤더(`x-access-token`)로 바꾸는 것은 **백엔드가 그 헤더를 inbound 로
 *   수용하는 것과 함께** 가야 한다(지금 수용 코드가 0건이라 프론트만 바꾸면 전량 401).
 * - `refresh` · `onUnauthorized` · `notifyActivity` 는 **창구만 열어 둔다.** 호출 지점 배선은
 *   후속이다. 특히 401 경로는 이번에 건드리지 않는다 — 근거는 아래 `onUnauthorized` 주석.
 *
 * 회귀 가드: `features/auth/__tests__/tokenHandoff.test.ts`
 */
import { isPortalEmbedChannel } from '@/lib/buildChannel';
import { useAuthStore } from '@/stores/useAuthStore';

/**
 * Host 가 주입하는 인계 창구의 모양.
 *
 * ⚠ 필드 이름을 바꾸지 말 것 — 이것은 우리 내부 타입이 아니라 **외부와 합의한 계약**이다.
 */
export interface TokenHandoffGateway {
  /** 현재 유효한 access token 을 돌려준다. 없으면 `null`. */
  getAccessToken(): string | null;
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
}

/**
 * 포털 채널 창구 — 언제나 Host 에 **다시 묻는다**.
 *
 * 값을 들고 있지 않는 것이 이 객체의 전부이자 존재 이유다. 파일 상단의 1~4 를 참조.
 */
export const portalTokenHandoff: TokenHandoffGateway = {
  getAccessToken() {
    return hostGateway?.getAccessToken() ?? null;
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
export function getAccessToken(): string | null {
  return resolveTokenHandoff().getAccessToken();
}
