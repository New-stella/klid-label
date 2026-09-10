// [@design ADR-012] [@design AC-1105] [@design AC-1106]
/**
 * 세션을 비우고 상위 시스템 로그인으로 보낸다 — **강제 로그아웃의 단일 지점**.
 *
 * 부르는 곳: 401 재시도 실패(요청 인터셉터) · 갱신 거절 · 남은 시간 0 · 다른 탭의 로그아웃 추종 ·
 * 사용자가 확인 절차를 거쳐 누른 「로그아웃」의 마지막 단계.
 *
 * ★ **관제 채널에서는 이동 직전에 이탈 경고를 끈다.** 세션이 끝난 뒤에는 저장할 수 없고, 브라우저
 *   확인창에서 멈추면 끝난 세션에 갇힌다. 자동 저장도 하지 않는다. 이동하지 못했으면(주소 미설정)
 *   경고를 되살린다 — 화면에 남는 사용자의 진짜 이탈까지 무방비로 두지 않는다.
 * ⚠ **포털 채널은 종전 그대로다** — 이탈 경고를 건드리지 않는다(세션 연장이 적용되지 않는 채널).
 *
 * ★ **공유 저장소 정리는 옵트인이다**(`clearStoredControlTokens`). 관제의 거절과 남은 시간 0 은
 *   관제 웹과 같은 결말이라 같은 출처의 토큰 키까지 지우지만, 일시 장애로 이 탭만 나가는 경로는
 *   관제 탭의 멀쩡한 세션을 끊지 않는다. 기본값이 **끄기**인 것은 「기존 호출부의 현재 동작」이
 *   그것이기 때문이다 — 새 플래그의 기본값은 종전 동작이어야 한다.
 *
 * 채널 추론은 종전 401 경로(`client.redirectToUpstreamLogin`)와 같게 **세션을 비운 뒤** 한다 —
 * 두 경로가 서로 다른 로그인 주소를 고르지 않게.
 *
 * ⚠ `controlSession` 과 서로 참조한다(그쪽이 이 함수를 부른다). 양쪽 다 **함수 선언을 호출 시점에**
 *   쓰므로 초기화 순서와 무관하다 — 모듈 최상위에서 서로의 값을 읽지 말 것.
 */
import { isPortalEmbedChannel } from '@/lib/buildChannel';
import { restoreLeaveWarnings, suppressLeaveWarnings } from '@/lib/unsavedWork';
import { useAuthStore } from '@/stores/useAuthStore';

import { clearControlTokensIfCurrent } from './controlSession';
import { detectChannel, redirectToUpstream } from './redirectToUpstream';

export interface TerminateSessionOptions {
  /**
   * 같은 출처 저장소의 관제 토큰 키(`klid-jwt-token` · `tokenInfo`)까지 지운다 — 관제 웹과 같은
   * 결말이라 같은 출처의 관제 탭도 함께 로그아웃된다. **거절·남은 시간 0 결말에서만 켠다.**
   * 지우기 직전 재읽기로 다른 탭이 이미 새 토큰을 저장했으면 지우지 않는다.
   */
  clearStoredControlTokens?: boolean;
}

/** @returns 실제로 이동했으면 `true`. */
export function terminateSession(options: TerminateSessionOptions = {}): boolean {
  const control = !isPortalEmbedChannel();
  if (control) suppressLeaveWarnings();
  // 저장소 비교 기준은 **비우기 전** 스토어 토큰이다 — 비운 뒤에는 무엇을 끝내려던 것인지 알 수 없다.
  if (control && options.clearStoredControlTokens) {
    clearControlTokensIfCurrent(useAuthStore.getState().token);
  }
  useAuthStore.getState().clear();
  const moved = redirectToUpstream(useAuthStore.getState().claims?.channel ?? detectChannel());
  if (!moved && control) restoreLeaveWarnings();
  return moved;
}
