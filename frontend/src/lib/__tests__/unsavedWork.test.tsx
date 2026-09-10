import { renderHook } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import {
  hasUnsavedWork,
  restoreLeaveWarnings,
  suppressLeaveWarnings,
  useBeforeUnloadWarning,
  useUnsavedWorkFlag,
} from '../unsavedWork';

/** [@design AC-1105] [@design AC-1106] 미저장 표식과 이탈 경고 억제의 회귀 가드. */
function fireBeforeUnload(): boolean {
  const ev = new Event('beforeunload', { cancelable: true });
  window.dispatchEvent(ev);
  return ev.defaultPrevented;
}

describe('unsavedWork', () => {
  it('활성인_동안_브라우저_이탈_경고를_건다', () => {
    const { rerender, unmount } = renderHook(({ on }) => useBeforeUnloadWarning(on), {
      initialProps: { on: true },
    });
    expect(fireBeforeUnload()).toBe(true);
    rerender({ on: false });
    expect(fireBeforeUnload()).toBe(false);
    unmount();
  });

  it('★세션을_끝내고_떠나는_경로가_억제하면_경고가_뜨지_않고_되살리면_다시_뜬다', () => {
    const { unmount } = renderHook(() => useBeforeUnloadWarning(true));
    suppressLeaveWarnings();
    expect(fireBeforeUnload()).toBe(false);
    restoreLeaveWarnings();
    expect(fireBeforeUnload()).toBe(true);
    unmount();
  });

  it('화면의_미저장_여부를_알리고_언마운트되면_지운다', () => {
    const { rerender, unmount } = renderHook(({ dirty }) => useUnsavedWorkFlag('screen', dirty), {
      initialProps: { dirty: true },
    });
    expect(hasUnsavedWork()).toBe(true);
    rerender({ dirty: false });
    expect(hasUnsavedWork()).toBe(false);
    rerender({ dirty: true });
    unmount();
    expect(hasUnsavedWork()).toBe(false);
  });
});
