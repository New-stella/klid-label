// 「영상 분석 설명 · 이벤트 어노테이션」 창의 열림 상태 — 라벨링·검수 두 화면이 공유한다.
// [@design UI-156] [@design UI-157] [@design SCREEN-005] [@design SCREEN-019]
//
// 상태를 창이 아니라 <b>화면</b>이 들고 있는 이유: 우측 메타 탭의 요약 카드가 같은 상태를 보고
// 버튼 문구(「크게 보기」/「창 앞으로 가져오기」/「창 펼치기」)와 상태 표시를 바꾼다. 창 안에
// 두면 창이 닫혔을 때 그 상태를 아는 주체가 사라진다.

import { useCallback, useMemo, useState } from 'react';

/**
 * 창 상태 네 가지.
 * - closed: 닫힘(창 미마운트 — 다시 열면 서버값으로 새로 시작한다)
 * - open: 떠 있음
 * - folded: 「잠시 접기」 또는 근거 프레임 이동으로 접힘(창은 마운트된 채 숨는다 — 입력값 보존)
 * - picking: 근거 지정 중(창을 숨기고 띠만 남긴다 — 역시 입력값 보존)
 */
export type AnnotationWindowState = 'closed' | 'open' | 'folded' | 'picking';

export interface AnnotationWindowControls {
  state: AnnotationWindowState;
  /** 창이 마운트돼 있는가 — 접힘·지정 중에도 값을 잃지 않으려면 마운트는 유지해야 한다. */
  mounted: boolean;
  /**
   * 요약 카드 버튼 하나가 부르는 동작 — 닫혀 있으면 열고, 접혀 있으면 펼치고, 떠 있으면 앞으로
   * 가져온다. 세 동작을 한 진입점에 모으는 것이 사양이다(버튼이 하나다).
   */
  openOrFocus: () => void;
  close: () => void;
  fold: () => void;
  expand: () => void;
  setPicking: (picking: boolean) => void;
  /** 창을 앞으로 가져온 횟수 — 창이 이 값이 바뀌는 것을 보고 포커스를 옮긴다. */
  focusRequestedAt: number;
}

export function useAnnotationWindow(): AnnotationWindowControls {
  const [state, setState] = useState<AnnotationWindowState>('closed');
  const [focusRequestedAt, setFocusRequestedAt] = useState(0);

  const openOrFocus = useCallback(() => {
    setState((prev) => (prev === 'picking' ? prev : 'open'));
    // 이미 열려 있으면 새로 만들지 않고 앞으로 가져온다(창은 동시에 하나다).
    setFocusRequestedAt(Date.now());
  }, []);

  const close = useCallback(() => setState('closed'), []);
  const fold = useCallback(() => setState((prev) => (prev === 'closed' ? prev : 'folded')), []);
  const expand = useCallback(() => setState((prev) => (prev === 'closed' ? prev : 'open')), []);
  const setPicking = useCallback(
    (picking: boolean) => setState((prev) => (prev === 'closed' ? prev : picking ? 'picking' : 'open')),
    [],
  );

  return useMemo(
    () => ({
      state,
      mounted: state !== 'closed',
      openOrFocus,
      close,
      fold,
      expand,
      setPicking,
      focusRequestedAt,
    }),
    [state, openOrFocus, close, fold, expand, setPicking, focusRequestedAt],
  );
}
