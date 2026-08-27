import { create } from 'zustand';

/**
 * 관리자 단기 유효창의 **화면 쪽 단일 저장소**. [@design API-194] [@design SCREEN-040]
 *
 * <h3>왜 컴포넌트 상태가 아니라 스토어인가</h3>
 * 유효창은 이제 연동 주소 카드 하나가 아니라 **관리 기능 공통 진입**이다. 진입 화면(`/admin`)이
 * 열어 준 창을 사용자 관리·연동 주소·업로드·패스워드 교체 화면이 함께 써야 하는데, 컴포넌트
 * 상태로 두면 라우트를 옮기는 순간 사라져 화면마다 다시 인증을 받게 된다.
 *
 * <h3>브라우저 저장소에 두지 않는다</h3>
 * localStorage/sessionStorage 에 두면 XSS 한 번으로 유효창이 통째로 넘어가고, 탭을 닫았다 열어도
 * 살아남아 「짧은 창」이라는 성질이 사라진다. 이 스토어는 **메모리에만** 있으므로 새로고침하면
 * 잠긴다(persist 미들웨어를 붙이지 말 것).
 *
 * <h3>유효 여부의 판정은 서버가 소유한다</h3>
 * 여기 담긴 만료 시각은 **남은 시간을 보여주기 위한 표시값**이다. 서버는 요청마다 토큰 서명과
 * 만료를 다시 보므로, 화면이 시계를 조작해도 서버 판정은 달라지지 않는다.
 */
export interface AdminSessionIssued {
  token: string;
  /** 서버가 알려준 만료 시각(ISO 8601). */
  expiresAt: string;
}

interface AdminSessionState {
  token: string | null;
  /** 만료 시각(epoch ms). 표시·잠금 편의용이며 판정의 진실원이 아니다. */
  expiresAtMs: number | null;
  open: (issued: AdminSessionIssued) => void;
  /** 유효창을 버린다 — 만료·서버 거부·패스워드 교체 성공 시 호출한다. */
  clear: () => void;
}

export const useAdminSessionStore = create<AdminSessionState>((set) => ({
  token: null,
  expiresAtMs: null,
  open: (issued) =>
    set({ token: issued.token, expiresAtMs: new Date(issued.expiresAt).getTime() }),
  clear: () => set({ token: null, expiresAtMs: null }),
}));

/**
 * 지금 유효창이 열려 있는지 — **렌더 밖에서** 판정할 때 쓴다(가드·요청 직전 확인).
 *
 * 렌더 안에서는 {@link useAdminSessionWindow} 를 쓴다. 이 함수는 1초 tick 을 구독하지 않으므로
 * 남은 시간이 0 이 되는 순간을 스스로 감지하지 못한다.
 */
export function isAdminSessionOpen(now: number = Date.now()): boolean {
  const { token, expiresAtMs } = useAdminSessionStore.getState();
  return Boolean(token) && expiresAtMs !== null && expiresAtMs > now;
}

/**
 * 지금 요청에 실을 토큰 — 열려 있지 않으면 `undefined`.
 *
 * ⚠ 만료된 토큰을 실어 보내지 않는다. 보내 봐야 서버가 거부하고, 그 거부가 화면에서는
 * 「이유를 알 수 없는 실패」로 보인다.
 */
export function currentAdminSessionToken(): string | undefined {
  return isAdminSessionOpen() ? (useAdminSessionStore.getState().token ?? undefined) : undefined;
}
