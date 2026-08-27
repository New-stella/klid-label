import { useCallback, useEffect, useState } from 'react';

import { openAdminSession } from '../api';
import { useAdminSessionStore } from '../store';

/** 남은 시간 표시를 1초 간격으로 갱신한다. */
const TICK_MS = 1000;

/** 유효창 길이 안내(분) — BE 기본값과 같다. 서버가 상한 30분을 강제한다. */
export const ADMIN_SESSION_TTL_MINUTES_HINT = 10;

export interface UseAdminSessionResult {
  /** 지금 관리 기능 쓰기가 열려 있는가. */
  unlocked: boolean;
  /** 요청에 실을 토큰. 잠겨 있으면 undefined. */
  token?: string;
  /** 남은 시간(초). 잠겨 있으면 0. */
  remainingSeconds: number;
  /** 남은 시간 표시 문자열 (`M:SS`). 잠겨 있으면 빈 문자열. */
  remainingLabel: string;
  /** 인증 진행 중 여부. */
  isOpening: boolean;
  /** 인증 실패 메시지. 성공하거나 다시 시도하면 사라진다. */
  error?: string;
  open: (adminPassword: string) => Promise<boolean>;
  /** 즉시 잠근다 — 서버가 403 으로 거절했을 때(이미 만료로 본 경우) 화면을 서버 판정에 맞춘다. */
  lock: () => void;
}

/**
 * 관리자 단기 유효창의 화면 상태 — **관리 기능 공통**. [@design API-194] [@design SCREEN-040]
 *
 * <h3>상태는 공유하고 진행·실패는 각자 갖는다</h3>
 * 토큰과 만료 시각은 {@link useAdminSessionStore} 한 곳에 있어 진입 화면이 연 창을 모든 관리
 * 화면이 함께 쓴다. 반면 「지금 인증 요청 중인가」와 「방금 실패 사유」는 그 다이얼로그를 띄운
 * 화면의 사정이라 훅 인스턴스마다 따로 둔다 — 공유하면 한 화면의 실패 문구가 다른 화면에 뜬다.
 *
 * <h3>남은 시간은 표시일 뿐이다</h3>
 * 여기서 0 이 되면 화면을 잠그지만 그건 **편의**다. 실제 유효성 판정은 서버가 요청마다 서명과
 * 만료를 다시 보고 하며, 클라이언트가 시계를 조작해도 서버 판정은 달라지지 않는다.
 */
export function useAdminSessionWindow(): UseAdminSessionResult {
  const token = useAdminSessionStore((s) => s.token);
  const expiresAtMs = useAdminSessionStore((s) => s.expiresAtMs);
  const openSession = useAdminSessionStore((s) => s.open);
  const clearSession = useAdminSessionStore((s) => s.clear);

  const [isOpening, setIsOpening] = useState(false);
  const [error, setError] = useState<string | undefined>();
  const [now, setNow] = useState(() => Date.now());

  const hasSession = Boolean(token) && expiresAtMs !== null;
  const remainingSeconds = hasSession
    ? Math.max(0, Math.floor(((expiresAtMs as number) - now) / 1000))
    : 0;
  const unlocked = hasSession && remainingSeconds > 0;

  // 유효창이 있는 동안에만 재렌더한다 — 잠긴 상태에서 타이머를 돌릴 이유가 없다.
  useEffect(() => {
    if (!hasSession) return;
    const id = window.setInterval(() => setNow(Date.now()), TICK_MS);
    return () => window.clearInterval(id);
  }, [hasSession]);

  // 만료되면 토큰을 버린다 — 만료된 값을 들고 있다가 실수로 전송하지 않게.
  useEffect(() => {
    if (hasSession && remainingSeconds <= 0) {
      clearSession();
    }
  }, [hasSession, remainingSeconds, clearSession]);

  const open = useCallback(
    async (adminPassword: string) => {
      setIsOpening(true);
      setError(undefined);
      try {
        const issued = await openAdminSession(adminPassword);
        openSession(issued);
        setNow(Date.now());
        return true;
      } catch (e) {
        // 패스워드 값은 절대 메시지에 싣지 않는다. 서버 문구를 그대로 쓰되 없으면 기본 문구.
        const message =
          (e as { message?: string })?.message || '관리자 인증에 실패했습니다. 다시 시도해 주세요.';
        setError(message);
        return false;
      } finally {
        setIsOpening(false);
      }
    },
    [openSession],
  );

  const lock = useCallback(() => clearSession(), [clearSession]);

  return {
    unlocked,
    token: unlocked ? (token ?? undefined) : undefined,
    remainingSeconds,
    remainingLabel: unlocked ? formatRemaining(remainingSeconds) : '',
    isOpening,
    error,
    open,
    lock,
  };
}

/**
 * 남은 시간 표기 `M:SS` — **이것이 정본**이다.
 *
 * 시안이 더한 게이지 막대는 색 단독 신호를 피하려던 보조 표현이라 구현에서 생략해도 사양 위반이
 * 아니다. 반대로 이 텍스트를 없애고 막대만 남기면 색만으로 정보를 전달하게 된다.
 */
function formatRemaining(totalSeconds: number): string {
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return `${minutes}:${String(seconds).padStart(2, '0')}`;
}
