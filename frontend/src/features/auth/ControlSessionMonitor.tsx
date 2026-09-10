// [@design ADR-012] [@design SHELL-001] [@design UC-041] [@design INT-013]
// [@design API-247] [@design API-246] [@design AC-1105] [@design AC-1106]
/**
 * 관제 채널 세션 만료 감시 + 연장 팝업 — **앱 최상단**에 한 번 탑재한다(관제 채널 빌드만).
 *
 * ★ 셸(`AppLayout`)이 아니라 앱 최상단인 이유: 가장 오래 머무는 라벨링 캔버스(`/label/:id`)가 셸 밖
 *   전체 화면이라, 셸에만 두면 정작 필요한 화면에서 팝업이 뜨지 않는다(사용자 확정).
 * ★ 로그인 전 화면(진입 · 오류 · 개발 로그인)과 세션이 없는 상태에서는 아무것도 하지 않는다.
 *
 * 흐름 규칙
 * - 1초 간격. 남은 시간 ≤ 임계면 팝업을 띄우되 **매 감시 주기마다 되풀이하지 않는다**(띄운 채 유지).
 * - 재표시 억제는 그 토큰 수명(만료 시각)에 묶인다 — 「로그인 연장」 성공이나 다른 탭 갱신으로 만료
 *   시각이 바뀌면 새 수명에서 다시 뜰 수 있다. 사용자가 「로그아웃」을 눌렀다가 미저장 확인에서
 *   **취소**하면 억제를 푼다(다음 주기에 임계 이하면 다시 뜬다).
 * - 남은 시간 0 → 팝업 닫고 강제 로그아웃(미저장 확인 없이).
 * - 갱신 불가 세션(refresh 토큰 없음 — 개발 로그인, **그리고 관제가 새 refresh 를 주지 않은 갱신
 *   뒤의 세션**)에는 팝업을 띄우지 않는다. 누를 수 있는 「연장」이 동작하지 않기 때문이다.
 *   만료 0 의 로그아웃은 그대로 적용된다.
 * - 강제 로그아웃(거절 · 남은 시간 0)은 같은 출처 저장소의 토큰 키까지 지운다([@design AC-1106]).
 *   사용자가 누른 「로그아웃」은 종전대로 로그아웃 중계 뒤 직접 지운다.
 */
import { useCallback, useEffect, useRef, useState } from 'react';

import { ConfirmDialog } from '@/components/common/ConfirmDialog';
import { resolveRouterBasename } from '@/lib/remoteMount';
import { hasUnsavedWork, suppressLeaveWarnings, useHasUnsavedWork } from '@/lib/unsavedWork';
import { useAuthStore } from '@/stores/useAuthStore';

import {
  DEFAULT_SESSION_TIME_MINUTES,
  clearControlTokens,
  getControlSessionTiming,
  installControlSessionStorageSync,
  refreshControlSession,
  requestControlLogout,
} from './controlSession';
import { SessionExpiryDialog } from './SessionExpiryDialog';
import { terminateSession } from './sessionTermination';

/** 감시 간격(ms) — 관제와 같다. */
export const SESSION_WATCH_INTERVAL_MS = 1000;

/** 로그인 전 화면 — 라우터 기준 경로. */
export const PRE_LOGIN_PATHS = ['/ingress', '/dev/login', '/forbidden'] as const;

/** 일시 장애 안내 — 백엔드 503 안내 문구와 같게 둔다. */
export const EXTEND_UNAVAILABLE_MESSAGE =
  '관제 서버에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.';

function currentAppPath(): string {
  const path = typeof window !== 'undefined' ? (window.location?.pathname ?? '/') : '/';
  const base = resolveRouterBasename();
  if (base && (path === base || path.startsWith(`${base}/`))) {
    return path.slice(base.length) || '/';
  }
  return path;
}

function isPreLoginPath(): boolean {
  const path = currentAppPath();
  return PRE_LOGIN_PATHS.some((p) => path === p || path.startsWith(`${p}/`));
}

interface PopupView {
  open: boolean;
  remainingMs: number;
  sessionTimeMinutes: number;
}

const CLOSED: PopupView = {
  open: false,
  remainingMs: 0,
  sessionTimeMinutes: DEFAULT_SESSION_TIME_MINUTES,
};

export function ControlSessionMonitor() {
  const [view, setView] = useState<PopupView>(CLOSED);
  const [extendError, setExtendError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [confirmOpen, setConfirmOpen] = useState(false);
  const unsaved = useHasUnsavedWork();

  /** 팝업을 이미 누른(억제한) 토큰 수명 — 그 만료 시각. */
  const suppressedForRef = useRef<number | null>(null);
  /** 지금 떠 있는 팝업의 만료 시각. */
  const shownForRef = useRef<number | null>(null);
  const lastExpiresAtRef = useRef<number | null>(null);
  const confirmOpenRef = useRef(false);
  const loggingOutRef = useRef(false);

  const tick = useCallback(() => {
    if (loggingOutRef.current) return;
    const session = useAuthStore.getState();
    if (!session.token || !session.claims || isPreLoginPath()) {
      shownForRef.current = null;
      setView((v) => (v.open ? CLOSED : v));
      return;
    }
    const timing = getControlSessionTiming();
    if (!timing) {
      shownForRef.current = null;
      setView((v) => (v.open ? CLOSED : v));
      return;
    }
    if (lastExpiresAtRef.current !== timing.expiresAt) {
      // 새 토큰 수명(이 탭 갱신·다른 탭 갱신) — 떠 있던 안내·오류는 옛 수명의 것이다.
      lastExpiresAtRef.current = timing.expiresAt;
      setExtendError(null);
    }
    const remaining = timing.expiresAt - Date.now();
    if (remaining <= 0) {
      shownForRef.current = null;
      confirmOpenRef.current = false;
      setConfirmOpen(false);
      setView(CLOSED);
      // 만료 — 관제 웹과 같은 결말이라 같은 출처의 토큰 키도 지운다([@design AC-1106]).
      terminateSession({ clearStoredControlTokens: true });
      return;
    }
    const eligible =
      timing.refreshable &&
      remaining <= timing.alarmMs &&
      suppressedForRef.current !== timing.expiresAt &&
      !confirmOpenRef.current;
    shownForRef.current = eligible ? timing.expiresAt : null;
    setView({
      open: eligible,
      remainingMs: remaining,
      sessionTimeMinutes: timing.sessionTimeMinutes,
    });
  }, []);

  useEffect(() => {
    tick();
    const id = window.setInterval(tick, SESSION_WATCH_INTERVAL_MS);
    const unsubscribe = installControlSessionStorageSync(tick);
    return () => {
      window.clearInterval(id);
      unsubscribe();
    };
  }, [tick]);

  const handleExtend = useCallback(async () => {
    const expiresAt = shownForRef.current;
    setBusy(true);
    setExtendError(null);
    const outcome = await refreshControlSession();
    setBusy(false);
    if (outcome.kind === 'rejected') {
      shownForRef.current = null;
      setView(CLOSED);
      // 거절 — 관제 웹과 같은 결말이라 같은 출처의 토큰 키도 지운다([@design AC-1106]).
      terminateSession({ clearStoredControlTokens: true });
      return;
    }
    if (outcome.kind === 'refreshed' || outcome.kind === 'adopted') {
      if (expiresAt !== null) suppressedForRef.current = expiresAt;
      tick();
      return;
    }
    // 일시 장애(503·미도달) — 현재 토큰으로 계속한다. 팝업은 유지하고 안내만 한다.
    setExtendError(EXTEND_UNAVAILABLE_MESSAGE);
  }, [tick]);

  const performUserLogout = useCallback(async () => {
    loggingOutRef.current = true;
    // 사용자가 확인 절차를 거친 뒤다 — 이동 시 브라우저 이탈 경고가 다시 뜨지 않게 한다.
    suppressLeaveWarnings();
    await requestControlLogout(useAuthStore.getState().token);
    clearControlTokens();
    if (!terminateSession()) loggingOutRef.current = false;
  }, []);

  const handleLogout = useCallback(() => {
    suppressedForRef.current = shownForRef.current;
    shownForRef.current = null;
    setView((v) => ({ ...v, open: false }));
    if (hasUnsavedWork()) {
      confirmOpenRef.current = true;
      setConfirmOpen(true);
      return;
    }
    void performUserLogout();
  }, [performUserLogout]);

  const handleConfirmCancel = useCallback(() => {
    // 취소는 「지금 나가지 않겠다」이지 「안내를 그만 받겠다」가 아니다 — 억제를 푼다.
    confirmOpenRef.current = false;
    setConfirmOpen(false);
    suppressedForRef.current = null;
  }, []);

  const handleConfirmLogout = useCallback(() => {
    confirmOpenRef.current = false;
    setConfirmOpen(false);
    void performUserLogout();
  }, [performUserLogout]);

  return (
    <>
      <SessionExpiryDialog
        open={view.open}
        remainingMs={view.remainingMs}
        sessionTimeMinutes={view.sessionTimeMinutes}
        hasUnsavedWork={unsaved}
        extendError={extendError}
        busy={busy}
        onExtend={() => void handleExtend()}
        onLogout={handleLogout}
      />
      <ConfirmDialog
        open={confirmOpen}
        title="저장하지 않은 작업이 있습니다"
        description="로그아웃하면 저장하지 않은 변경 내용이 사라집니다. 로그아웃하시겠습니까?"
        confirmLabel="로그아웃"
        cancelLabel="취소"
        variant="danger"
        closeOnBackdrop={false}
        onConfirm={handleConfirmLogout}
        onCancel={handleConfirmCancel}
      />
    </>
  );
}
