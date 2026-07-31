// Phase 4 (N-3) — 단축키 차단은 **fail-closed** 여야 한다.
//
// `useLabelingShortcuts` 는 차단 여부를 상위가 넘긴 렌더 값(`blocked`)으로 판정했다. busy 는
// 이벤트 핸들러 실행 시점(렌더 사이)에 시작될 수 있고, 그 커밋이 화면에 반영되기 전에 도착한
// keydown 은 `blocked=false` 로 보여 그대로 실행된다 — 삭제(R/Del)·도구 전환·프레임 이동이
// 차단을 뚫는 짧은 창이다. 같은 파일의 ESC 분기와 `OverlayLayer`·`LabelingPage` 는 이미
// 실시간 store 값을 함께 보고 있었다(fail-closed). 판정 축을 통일한다.
//
// 이 테스트는 "렌더 값은 아직 false 인데 store 는 이미 busy" 상태를 **재렌더 없이** 만들어
// 그 창을 그대로 재현한다.

import { act, renderHook } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { type ReactNode } from 'react';

import { useLabelStore } from '@/stores/useLabelStore';

import { useLabelingShortcuts } from '../hooks/useLabelingShortcuts';
import { ToolType, type Label } from '../types';

function press(key: string, opts: KeyboardEventInit = {}) {
  window.dispatchEvent(new KeyboardEvent('keydown', { key, ...opts }));
}

function makeWrapper() {
  const qc = new QueryClient({
    defaultOptions: {
      queries: { retry: false, gcTime: 0, staleTime: 0, refetchOnWindowFocus: false },
      mutations: { retry: false },
    },
  });
  function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={qc}>{children}</QueryClientProvider>;
  }
  return Wrapper;
}

function bboxLabel(id: string): Label {
  return {
    id,
    frameNo: 0,
    classId: 1,
    className: 'car',
    source: 'MANUAL',
    shape: { type: 'BBOX', left: 10, top: 20, right: 110, bottom: 220 },
  };
}

/**
 * busy 를 **재렌더 없이** 시작한다 — 상위가 넘긴 `blocked` 는 false 로 남는다.
 * (실제로는 핸들러 안에서 beginBusy 가 호출되고, 그 리렌더가 커밋되기 전 keydown 이 도착하는 창.)
 */
function startBusyWithoutRerender() {
  useLabelStore.getState().beginBusy('SAVE', { srcSn: 1 });
}

describe('useLabelingShortcuts — busy 판정 fail-closed (N-3)', () => {
  beforeEach(() => {
    useLabelStore.getState().reset();
  });

  afterEach(() => {
    useLabelStore.getState().reset();
    vi.restoreAllMocks();
  });

  it('busy_시작_직후_리렌더_전_단축키도_차단된다', () => {
    // given: 화면은 아직 blocked=false 로 렌더돼 있다.
    const onNextFrame = vi.fn();
    const onSave = vi.fn();
    renderHook(() => useLabelingShortcuts({ onNextFrame, onSave }, { blocked: false }), {
      wrapper: makeWrapper(),
    });
    act(() => {
      useLabelStore.getState().addLabel(bboxLabel('l-1'));
      useLabelStore.getState().selectLabel('l-1');
    });
    startBusyWithoutRerender();

    // when: 그 창에 단축키가 도착한다.
    act(() => {
      press('d'); // 다음 프레임
      press('b'); // 도구 전환
      press('r'); // 선택 라벨 삭제
      press('s', { ctrlKey: true }); // 저장
    });

    // then: 전부 무시된다(렌더 값이 아니라 실시간 store 를 본다).
    expect(onNextFrame).not.toHaveBeenCalled();
    expect(onSave).not.toHaveBeenCalled();
    expect(useLabelStore.getState().activeTool).toBe(ToolType.SELECT);
    expect(useLabelStore.getState().labels).toHaveLength(1);
  });

  it('busy가_없으면_같은_단축키가_정상_동작한다', () => {
    // 무회귀 — fail-closed 판정이 평상시 단축키를 죽이면 안 된다.
    const onNextFrame = vi.fn();
    renderHook(() => useLabelingShortcuts({ onNextFrame }, { blocked: false }), {
      wrapper: makeWrapper(),
    });

    act(() => {
      press('d');
      press('b');
    });

    expect(onNextFrame).toHaveBeenCalledTimes(1);
    expect(useLabelStore.getState().activeTool).toBe(ToolType.BBOX);
  });

  it('busy가_풀리면_즉시_다시_동작한다', () => {
    const onNextFrame = vi.fn();
    renderHook(() => useLabelingShortcuts({ onNextFrame }, { blocked: false }), {
      wrapper: makeWrapper(),
    });
    startBusyWithoutRerender();
    act(() => press('d'));
    expect(onNextFrame).not.toHaveBeenCalled();

    act(() => {
      useLabelStore.getState().cancelBusy();
      press('d');
    });

    expect(onNextFrame).toHaveBeenCalledTimes(1);
  });
});
