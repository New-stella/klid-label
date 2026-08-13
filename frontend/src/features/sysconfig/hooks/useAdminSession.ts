import { useCallback, useEffect, useMemo, useState } from 'react';

import { openAdminSession } from '../api';
import type { AdminSession } from '../types';

/** 남은 시간 표시를 1초 간격으로 갱신한다. */
const TICK_MS = 1000;

export interface UseAdminSessionResult {
  /** 지금 편집이 열려 있는가 (화면 잠금 해제 여부). */
  unlocked: boolean;
  /** 저장 요청에 실을 토큰. 잠겨 있으면 undefined. */
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
  /** 즉시 잠근다 — 저장이 403 으로 거절됐을 때(서버가 이미 만료로 본 경우) 화면을 맞춘다. */
  lock: () => void;
}

/**
 * R11 — 관리자 단기 유효창의 화면 상태.
 *
 * <h3>토큰을 브라우저 저장소에 두지 않는다</h3>
 * localStorage/sessionStorage 에 두면 XSS 한 번으로 유효창이 통째로 넘어가고, 탭을 닫았다 열어도
 * 살아남아 "짧은 창"이라는 성질이 사라진다. 컴포넌트 상태로만 들고 있어 **새로고침하면 잠긴다**.
 *
 * <h3>남은 시간은 표시일 뿐이다</h3>
 * 여기서 0 이 되면 화면을 잠그지만, 그건 **편의**다. 실제 유효성 판정은 서버가 저장 요청마다
 * 서명과 만료를 다시 보고 하며, 클라이언트가 시계를 조작해도 서버 판정은 달라지지 않는다.
 */
export function useAdminSession(): UseAdminSessionResult {
  const [session, setSession] = useState<AdminSession | null>(null);
  const [isOpening, setIsOpening] = useState(false);
  const [error, setError] = useState<string | undefined>();
  const [now, setNow] = useState(() => Date.now());

  const expiresAtMs = useMemo(
    () => (session ? new Date(session.expiresAt).getTime() : 0),
    [session],
  );
  const remainingSeconds = session ? Math.max(0, Math.floor((expiresAtMs - now) / 1000)) : 0;
  const unlocked = Boolean(session) && remainingSeconds > 0;

  // 유효창이 열려 있는 동안에만 재렌더한다 — 잠긴 상태에서 타이머를 돌릴 이유가 없다.
  useEffect(() => {
    if (!session) return;
    const id = window.setInterval(() => setNow(Date.now()), TICK_MS);
    return () => window.clearInterval(id);
  }, [session]);

  // 만료되면 토큰을 버린다 — 만료된 값을 들고 있다가 실수로 전송하지 않게.
  useEffect(() => {
    if (session && remainingSeconds <= 0) {
      setSession(null);
    }
  }, [session, remainingSeconds]);

  const open = useCallback(async (adminPassword: string) => {
    setIsOpening(true);
    setError(undefined);
    try {
      const issued = await openAdminSession(adminPassword);
      setSession(issued);
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
  }, []);

  const lock = useCallback(() => setSession(null), []);

  return {
    unlocked,
    token: unlocked ? session?.token : undefined,
    remainingSeconds,
    remainingLabel: unlocked ? formatRemaining(remainingSeconds) : '',
    isOpening,
    error,
    open,
    lock,
  };
}

function formatRemaining(totalSeconds: number): string {
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return `${minutes}:${String(seconds).padStart(2, '0')}`;
}
