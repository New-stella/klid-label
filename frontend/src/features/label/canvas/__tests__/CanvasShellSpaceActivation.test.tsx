// Phase 3 DEV_FIX 2차 NF-2 — 스페이스 팬 홀드가 **버튼의 기본 키보드 활성화**를 삼키면 안 된다.
//
// 캔버스는 Space 를 팬(뷰 이동) 홀드로 쓰면서 window keydown 을 preventDefault 한다. 버튼의 Space
// 활성화는 keyup 에 일어나므로, keydown 을 막으면 활성화가 통째로 사라진다 — 진행 오버레이의
// '작업 취소' 버튼이 자동 포커스를 받는데도 ESC 로만 눌리는 상태가 된다(주석은 "키보드 사용자가
// 즉시 취소 가능"이라고 적혀 있었다).
//
// 함께 고정: 팬 홀드 자체는 그대로 살아 있어야 한다(캔버스/본문 포커스에서 Space = grab).

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createElement, type ReactNode } from 'react';

vi.mock('react-konva', () => {
  const passthrough = (name: string) => {
    const KonvaMock = ({
      children,
      onWheel: _onWheel,
      listening: _listening,
      ...domRest
    }: {
      children?: ReactNode;
      onWheel?: unknown;
      listening?: unknown;
      [key: string]: unknown;
    }) => createElement('div', { 'data-konva': name, ...domRest }, children);
    KonvaMock.displayName = `KonvaMock(${name})`;
    return KonvaMock;
  };
  return {
    Stage: passthrough('Stage'),
    Layer: passthrough('Layer'),
    Image: passthrough('Image'),
    Rect: passthrough('Rect'),
    Line: passthrough('Line'),
    Circle: passthrough('Circle'),
    Group: passthrough('Group'),
    Transformer: passthrough('Transformer'),
  };
});

import { isEditBlockedState, useLabelStore } from '@/stores/useLabelStore';

import { CanvasShell } from '../CanvasShell';
import { BUSY_OVERLAY_DELAY_MS } from '../../busyPolicy';
import { BusyOverlay } from '../../components/BusyOverlay';
import type { FrameSummary } from '../../types';

const SRC_SN = 1;
const frame: FrameSummary = { frameNo: 0, srcSn: SRC_SN, thumbnailUrl: '', imageUrl: '' };

/** LabelingPage 와 같은 배선 — 캔버스와 진행 오버레이가 한 화면에 공존한다. */
function Harness() {
  const busyKind = useLabelStore((s) =>
    isEditBlockedState(s, SRC_SN) ? (s.busy?.kind ?? null) : null,
  );
  const busyStartedAt = useLabelStore((s) =>
    isEditBlockedState(s, SRC_SN) ? (s.busy?.startedAt ?? undefined) : undefined,
  );
  const cancelBusy = useLabelStore((s) => s.cancelBusy);
  return (
    <>
      <CanvasShell frame={frame} width={800} height={600} labels={[]} />
      <BusyOverlay kind={busyKind} startedAt={busyStartedAt} onCancel={cancelBusy} />
    </>
  );
}

/** 진행 오버레이가 이미 떠 있는 시점(지연 창 경과)으로 작업을 시작한다. */
function startAgedBusy() {
  act(() => {
    useLabelStore.getState().beginBusy('SAVE', { srcSn: SRC_SN });
    const busy = useLabelStore.getState().busy;
    if (busy === null) throw new Error('busy 가 없습니다');
    useLabelStore.setState({
      busy: { ...busy, startedAt: busy.startedAt - (BUSY_OVERLAY_DELAY_MS + 1000) },
    });
  });
}

beforeEach(() => {
  useLabelStore.getState().reset();
  useLabelStore.setState({ busy: null, busyGeneration: 0 });
});

afterEach(() => {
  useLabelStore.getState().reset();
});

describe('NF-2 — 스페이스 팬 홀드와 버튼 활성화', () => {
  it('오버레이_취소_버튼에서_Space로_작업이_취소된다', async () => {
    const user = userEvent.setup();
    render(<Harness />);
    startAgedBusy();
    await screen.findByTestId('busy-overlay');
    const cancelButton = screen.getByRole('button', { name: '작업 취소' });
    expect(document.activeElement).toBe(cancelButton);

    await user.keyboard(' ');

    expect(useLabelStore.getState().busy).toBeNull();
  });

  it('버튼_포커스_중_Space는_팬_홀드를_켜지_않는다', async () => {
    // 버튼 활성화와 팬 홀드가 동시에 걸리면 "취소하면서 뷰가 잡히는" 이중 반응이 된다.
    render(<Harness />);
    startAgedBusy();
    await screen.findByTestId('busy-overlay');
    const cancelButton = screen.getByRole('button', { name: '작업 취소' });

    fireEvent.keyDown(cancelButton, { code: 'Space', key: ' ' });

    expect(screen.getByTestId('canvas-shell').style.cursor).toBe('');
  });

  it('캔버스_포커스에서는_Space_팬_홀드가_그대로_동작한다', () => {
    // 무회귀 — 팬(뷰 이동)은 이 화면의 기존 조작이다. 버튼 예외가 팬을 죽이면 안 된다.
    render(<Harness />);
    const shell = screen.getByTestId('canvas-shell');
    expect(shell.style.cursor).toBe('');

    fireEvent.keyDown(window, { code: 'Space', key: ' ' });

    expect(shell.style.cursor).toBe('grab');
  });
});
