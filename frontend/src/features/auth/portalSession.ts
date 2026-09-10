// [@design INT-013]
/**
 * 포털 채널 세션 되맞춤 — 스토어의 세션을 <b>Host 인계 창구의 현재 토큰</b>과 맞춘다.
 *
 * ## 왜 필요한가 (되돌리기 전에 반드시 읽을 것)
 *
 * 포털 채널에서 `useAuthStore` 의 `token`·`claims` 는 <b>진실원이 아니라 거울</b>이다. 진실원은
 * Host 메모리이고, 우리는 그것을 한 번 베껴 두었을 뿐이다. 그런데 그 거울을 <b>채우는 곳은
 * 하나뿐인데 비우는 곳은 셋</b>이라 균형이 맞지 않았다:
 *
 * | 방향 | 자리 |
 * |---|---|
 * | 채움 | 진입 화면(`SessionIngressPage`) 1회 — 그런데 <b>포털 채널 가드는 그 화면으로 가지 않는다</b> |
 * | 비움 | 요청 인터셉터의 401 · `RoleGuard` 만료 · `AuthenticatedGuard` 만료 |
 *
 * 그래서 <b>한 번이라도 비워지면 영영 다시 차지 않는다.</b> 그 상태의 모든 라우트 가드는
 * 「인증이 필요합니다 / 로그인 정보를 확인할 수 없습니다」 안내를 그린다. 사용자에게는
 * <b>멀쩡히 쓰던 화면이 갑자기 인증 안내로 바뀌는</b> 간헐적 결함으로 보인다 —
 * 되살릴 방법은 새로고침뿐이다(그때 Host 가 다시 토큰을 건네므로).
 *
 * 게다가 그 자리의 탈출구(상위 시스템 로그인으로의 이동)는 <b>포털 로그인 주소가 설정되지
 * 않은 형상에서 아무 일도 하지 않는다</b>(`redirectToUpstream` 은 그때 `false` 를 돌려준다).
 * 즉 안내 화면이 곧 막다른 길이다.
 *
 * ## 이 모듈이 하는 일
 *
 * 「거울이 비었으면 <b>진실원에 다시 묻는다</b>」 하나다. 설계가 이미 그렇게 규정하고 있다 —
 * *"Remote 는 매 호출 시점에 토큰 획득 창구로 토큰을 취득한다. 자체 캐시를 두지 않는다"*
 * (`INT-013`). 요청 헤더 축은 이미 그 규정을 지키고 있었고(`lib/api/client` 가 매번 창구에
 * 묻는다), <b>화면 가드 축만 한 번 베낀 값에 머물러</b> 있었다. 그 비대칭을 없앤다.
 *
 * ## 되풀이(loop)를 어떻게 막는가 — `rejectedToken`
 *
 * 「비었으면 다시 묻는다」만으로는 <b>서버가 거부한 토큰을 무한히 되집는다</b>:
 * 401 → 거울 비움 → 되맞춤이 같은 토큰을 다시 채움 → 요청 → 401 → …
 *
 * 그래서 <b>이미 거부당한 토큰과 같은 값이면 다시 채우지 않는다.</b> 되맞춤이 실제로 무언가를
 * 하는 경우는 두 가지뿐이다:
 *
 *   1. <b>거울이 처음부터 비어 있다</b> — Host 가 창구를 늦게 주입한 경우(원격 모듈로 실릴 때).
 *   2. <b>Host 가 토큰을 갈았다</b> — 창구의 값이 거부당한 값과 다르다. 곧 되살아나야 할 세션이다.
 *
 * Host 가 여전히 죽은 토큰을 들고 있으면 되맞춤은 아무것도 하지 않고, 안내가 그대로 남는다
 * (그것이 옳다 — 그 상황은 실제로 인증이 끊긴 것이다).
 *
 * ⚠ <b>관제 채널에서는 통째로 no-op 이다.</b> 거기서는 스토어가 거울이 아니라 인계받은 값의
 *   보관처이고(같은 출처 저장소가 곧 인계 채널), 이 문제 자체가 성립하지 않는다.
 *
 * 회귀 가드: `features/auth/__tests__/portalSession.test.ts` ·
 *            `router/__tests__/portalSessionRecovery.test.tsx`
 */
import { isPortalEmbedChannel } from '@/lib/buildChannel';
import { useAuthStore } from '@/stores/useAuthStore';

import { getAccessToken } from './tokenHandoff';

/**
 * 서버가 거부한(401) 토큰. 이 값과 같은 토큰은 되맞춤이 다시 채우지 않는다.
 *
 * 모듈 스코프인 이유: 이것은 <b>세션 상태가 아니라 「방금 무엇이 거부당했는가」라는 사실</b>이라
 * 화면이 읽을 값이 아니고, 스토어에 넣으면 거울을 비우는 `clear()` 가 이 값까지 함께 지운다.
 */
let rejectedToken: string | null = null;

/**
 * 방금 401 로 거부당한 토큰을 기록한다 — 요청 인터셉터가 거울을 비우기 <b>직전에</b> 부른다.
 *
 * @param token 요청에 실제로 실어 보냈던 토큰. 알 수 없으면 `null`(그러면 되맞춤을 막지 않는다).
 */
export function markPortalTokenRejected(token: string | null): void {
  rejectedToken = token;
}

/** 거부 기록을 지운다 — 테스트 정리용. */
export function clearPortalTokenRejection(): void {
  rejectedToken = null;
}

/**
 * 스토어 세션을 Host 창구의 현재 토큰과 맞춘다.
 *
 * <p>★ <b>비동기다.</b> 포털 채널의 진실원이 Host 메모리이고 그 값을 «물어서» 받기 때문이다.
 * 호출부는 전부 효과(`useEffect`) 안이라 기다릴 수 있다 — 렌더 중에 부르지 않는다는 기존
 * 규약이 그대로 이 전환을 가능하게 했다.
 *
 * @returns 맞춘 결과 <b>쓸 수 있는 세션이 있는지</b>. 관제 채널이면 언제나 `false`(no-op).
 */
/**
 * 부팅 순서 — <b>포털 채널만</b> 창구를 먼저 맞추고, 그 밖 채널은 <b>즉시</b> 이어간다.
 *
 * <p>★ 이 함수가 따로 있는 이유는 <b>관제 채널의 동작을 한 틱도 늦추지 않기</b> 위해서다.
 * 되맞춤이 비동기가 되면서 {@code sync().then(next)} 로 이으면 관제 채널에서도 {@code next}
 * (= 세션 복원 · 저장소 hydrate)가 마이크로태스크 뒤로 밀린다. 그 사이 앱은 로딩만 그리므로
 * 사람 눈에는 같지만, <b>「관제 채널은 한 글자도 바뀌지 않는다」는 이 축의 불변식</b>이 깨지고
 * 부팅 순서를 동기로 전제한 회귀 가드가 함께 무너진다.
 *
 * <p>⚠ 채널 판정을 호출부(`App`)로 옮기지 말 것 — 판정이 두 벌이 되면 한쪽만 갱신된다.
 * 그 판정은 이 모듈이 이미 갖고 있으므로 순서도 여기서 소유한다.
 *
 * <p>⚠ 포털 채널에서 {@code catch} 가 붙는 이유: 창구는 <b>Host 가 구현하는 남의 코드</b>라
 * 던질 수 있고, 그것이 새어 나가면 {@code next} 가 영영 불리지 않아 화면이 <b>로딩에
 * 고착</b>된다. 토큰을 못 얻는 것보다 나쁜 결말이므로 삼키고 진행한다.
 *
 * @param next 세션이 맞춰진 뒤(또는 관제 채널이면 즉시) 이어서 할 일.
 */
export function syncPortalSessionThen(next: () => void): void {
  if (!isPortalEmbedChannel()) {
    next();
    return;
  }
  void syncPortalSessionFromHandoff()
    .catch(() => false)
    .then(next);
}

export async function syncPortalSessionFromHandoff(): Promise<boolean> {
  if (!isPortalEmbedChannel()) return false;

  // ⚠ **`await` 다.** 포털 창구는 Host 에 묻고 그 답이 Promise 로 온다. 기다리지 않으면
  //   Promise 객체가 그대로 `setToken` 에 들어가 JWT 해독에서 터진다(실측).
  const token = await getAccessToken();
  const { token: mirrored, claims } = useAuthStore.getState();

  if (!token) {
    // Host 가 토큰을 들고 있지 않다 — 거울에 남은 값은 이미 죽은 것이다.
    if (mirrored !== null || claims !== null) useAuthStore.getState().clear();
    return false;
  }

  // 거부당한 그 토큰이다 — 다시 채우면 401 되풀이가 된다.
  if (token === rejectedToken) return false;

  // 이미 같은 값을 들고 있고 해독까지 돼 있다 — 할 일이 없다.
  if (token === mirrored && claims !== null) return true;

  // 해독·검증은 스토어가 소유한다(형식이 어긋난 토큰은 스토어가 스스로 거부한다).
  useAuthStore.getState().setToken(token);
  const next = useAuthStore.getState().claims;
  if (next !== null) rejectedToken = null;
  return next !== null;
}
