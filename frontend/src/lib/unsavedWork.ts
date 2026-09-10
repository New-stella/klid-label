// [@design ADR-012] [@design SHELL-001] [@design AC-1105] [@design AC-1106]
/**
 * 「저장하지 않은 작업이 있는가」와 「브라우저 이탈 경고」의 **앱 전역 단일 지점**.
 *
 * ## 왜 전역이어야 하나
 * 관제 채널의 세션 연장 팝업은 **앱 최상단**에 떠 있고, 미저장 편집은 **화면 안**(라벨링 캔버스 등)에
 * 있다. 팝업은 그 사실을 알아야 ①본문에 「저장하지 않은 작업이 있습니다」를 보이고 ②사용자가 누른
 * 「로그아웃」에 확인 절차를 붙인다. 화면이 자기 상태를 여기에 알리고 팝업이 여기서 읽는다.
 *
 * ## 이탈 경고 억제 — 강제 로그아웃은 확인창에서 멈추지 않는다
 * 세션이 이미 끝났으면(갱신 거절·재시도 401·남은 시간 0) 저장할 수 없다. 그 상태에서 브라우저의
 * `beforeunload` 확인창이 뜨면 사용자는 **끝난 세션에 갇힌다.** 그래서 세션을 끝내는 쪽이 이동 직전에
 * {@link suppressLeaveWarnings} 를 부르고, 경고를 거는 쪽({@link useBeforeUnloadWarning})이 그 표식을
 * 확인한다. 사용자가 누른 「로그아웃」도 **확인 절차를 거친 뒤**이므로 같은 표식을 쓴다.
 *
 * ⚠ `beforeunload` 핸들러를 화면에서 직접 `window.addEventListener` 로 걸지 말 것 — 그러면 이 표식을
 *   모르는 경고가 생겨 강제 로그아웃이 다시 확인창에 걸린다.
 *
 * 회귀 가드: `lib/__tests__/unsavedWork.test.tsx` · `features/auth/__tests__/ControlSessionMonitor.test.tsx`
 */
import { useEffect } from 'react';
import { create } from 'zustand';

interface UnsavedWorkState {
  /** 출처별 미저장 여부. 키는 화면이 정한 식별자다. */
  sources: Record<string, boolean>;
  setSource: (id: string, dirty: boolean) => void;
}

export const useUnsavedWorkStore = create<UnsavedWorkState>((set) => ({
  sources: {},
  setSource: (id, dirty) =>
    set((state) => {
      if (dirty) return { sources: { ...state.sources, [id]: true } };
      if (!(id in state.sources)) return {};
      const next = { ...state.sources };
      delete next[id];
      return { sources: next };
    }),
}));

/** 어느 출처든 미저장 편집이 있는가. */
export function hasUnsavedWork(): boolean {
  return Object.values(useUnsavedWorkStore.getState().sources).some(Boolean);
}

/** 렌더에서 구독하는 형태 — 팝업 본문 경고가 편집 상태를 따라 즉시 바뀌게. */
export function useHasUnsavedWork(): boolean {
  return useUnsavedWorkStore((s) => Object.values(s.sources).some(Boolean));
}

/**
 * 화면의 미저장 여부를 전역에 알린다. 언마운트되면 자기 출처를 지운다.
 * @param id 출처 식별자 — 같은 화면이 두 번 뜨지 않는 한 고정 문자열이면 된다.
 */
export function useUnsavedWorkFlag(id: string, dirty: boolean): void {
  useEffect(() => {
    useUnsavedWorkStore.getState().setSource(id, dirty);
    return () => useUnsavedWorkStore.getState().setSource(id, false);
  }, [id, dirty]);
}

let leaveWarningsSuppressed = false;

/** 이탈 경고를 끈다 — 세션을 끝내고 떠나는 경로만 부른다. */
export function suppressLeaveWarnings(): void {
  leaveWarningsSuppressed = true;
}

/**
 * 억제를 푼다 — 떠나려 했으나 **실제로 떠나지 못한** 경우(이동 주소 미설정 등)에만 부른다.
 * 풀지 않으면 그 뒤 진짜 이탈에서도 경고가 영영 뜨지 않는다.
 */
export function restoreLeaveWarnings(): void {
  leaveWarningsSuppressed = false;
}

export function areLeaveWarningsSuppressed(): boolean {
  return leaveWarningsSuppressed;
}

/**
 * 탭·창을 닫거나 문서를 떠날 때 브라우저 기본 확인창을 건다(`active` 인 동안만).
 * 세션을 끝내고 떠나는 경로에서는 {@link suppressLeaveWarnings} 가 이 경고를 끈다.
 */
export function useBeforeUnloadWarning(active: boolean): void {
  useEffect(() => {
    if (!active) return;
    const handler = (e: BeforeUnloadEvent) => {
      if (leaveWarningsSuppressed) return;
      e.preventDefault();
      // 일부 브라우저는 returnValue 설정 필요 — 메시지는 브라우저가 결정
      e.returnValue = '';
    };
    window.addEventListener('beforeunload', handler);
    return () => window.removeEventListener('beforeunload', handler);
  }, [active]);
}

/** 시험 전용 — 전역 상태를 초기화한다. */
export function resetUnsavedWorkForTest(): void {
  leaveWarningsSuppressed = false;
  useUnsavedWorkStore.setState({ sources: {} });
}
