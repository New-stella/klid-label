import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { useLabelStore } from '@/stores/useLabelStore';

import { useLabelingShortcuts } from '../hooks/useLabelingShortcuts';
import { ToolType } from '../types';

function press(key: string, opts: KeyboardEventInit = {}) {
  const ev = new KeyboardEvent('keydown', { key, ...opts });
  window.dispatchEvent(ev);
}

describe('useLabelingShortcuts', () => {
  beforeEach(() => {
    useLabelStore.getState().reset();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('단축키_B_누르면_activeTool_BBOX', () => {
    renderHook(() => useLabelingShortcuts());
    act(() => press('b'));
    expect(useLabelStore.getState().activeTool).toBe(ToolType.BBOX);
  });

  it('단축키_P_누르면_POLYGON', () => {
    renderHook(() => useLabelingShortcuts());
    act(() => press('p'));
    expect(useLabelStore.getState().activeTool).toBe(ToolType.POLYGON);
  });

  it('단축키_S_누르면_SELECT', () => {
    renderHook(() => useLabelingShortcuts());
    useLabelStore.getState().setActiveTool(ToolType.BBOX);
    act(() => press('s'));
    expect(useLabelStore.getState().activeTool).toBe(ToolType.SELECT);
  });

  it('단축키_Ctrl+S_저장_트리거', () => {
    const onSave = vi.fn();
    renderHook(() => useLabelingShortcuts({ onSave }));
    act(() => press('s', { ctrlKey: true }));
    expect(onSave).toHaveBeenCalledTimes(1);
  });

  it('단축키_화살표_프레임_이동', () => {
    const onPrev = vi.fn();
    const onNext = vi.fn();
    renderHook(() => useLabelingShortcuts({ onPrevFrame: onPrev, onNextFrame: onNext }));
    act(() => press('ArrowLeft'));
    act(() => press('ArrowRight'));
    expect(onPrev).toHaveBeenCalledTimes(1);
    expect(onNext).toHaveBeenCalledTimes(1);
  });

  it('Ctrl+Z_undo_트리거', () => {
    renderHook(() => useLabelingShortcuts());
    const store = useLabelStore.getState();
    act(() => {
      store.addLabel({
        id: 'a',
        frameNo: 1,
        classId: 1,
        className: 'car',
        source: 'MANUAL',
        shape: { type: 'BBOX', left: 0, top: 0, right: 10, bottom: 10 },
      });
    });
    expect(useLabelStore.getState().labels).toHaveLength(1);
    act(() => press('z', { ctrlKey: true }));
    expect(useLabelStore.getState().labels).toHaveLength(0);
  });

  it('input_포커스_상태에서는_단축키_무시', () => {
    renderHook(() => useLabelingShortcuts());
    const input = document.createElement('input');
    document.body.appendChild(input);
    input.focus();

    const ev = new KeyboardEvent('keydown', { key: 'b', bubbles: true });
    Object.defineProperty(ev, 'target', { value: input });
    act(() => {
      window.dispatchEvent(ev);
    });

    // SELECT 그대로 유지
    expect(useLabelStore.getState().activeTool).toBe(ToolType.SELECT);
    document.body.removeChild(input);
  });

  it('Del_누르면_선택된_라벨_삭제', () => {
    renderHook(() => useLabelingShortcuts());
    const store = useLabelStore.getState();
    act(() => {
      store.addLabel({
        id: 'x',
        frameNo: 1,
        classId: 1,
        className: 'car',
        source: 'MANUAL',
        shape: { type: 'BBOX', left: 0, top: 0, right: 10, bottom: 10 },
      });
      useLabelStore.getState().selectLabel('x');
    });
    act(() => press('Delete'));
    expect(useLabelStore.getState().labels).toHaveLength(0);
  });
});
