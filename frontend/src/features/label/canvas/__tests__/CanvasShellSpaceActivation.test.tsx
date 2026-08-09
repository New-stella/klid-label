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

vi.mock('react-konva', async () => (await import('@/test/konvaMock')).createKonvaMock());

import { isEditBlockedState, useLabelStore } from '@/stores/useLabelStore';

import { CanvasShell } from '../CanvasShell';
import { BUSY_OVERLAY_DELAY_MS } from '../../busyPolicy';
import { BusyOverlay } from '../../components/BusyOverlay';
import { ToolType, type FrameSummary } from '../../types';

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

  // ── N-1 회귀 가드 ────────────────────────────────────────────────
  // 툴바 버튼이 포커스를 쥔 동안 Space 는 **팬이 아니라 그 버튼의 활성화**로 간다. 이것은 결함이
  // 아니라 APG/WCAG 표준 동작이며(포커스된 컨트롤의 기본 키를 화면이 가로채지 않는다) NF-2 에서
  // 의도적으로 도입한 예외다. 대신 "버튼에 포커스가 남아 팬이 안 걸린다"는 사실 자체를 고정해
  // 두지 않으면, 반대로 툴바 클릭 시 포커스를 캔버스로 옮기는 '수정'이 들어와 키보드 사용자의
  // 포커스가 조용히 도둑맞는다(활성화 시 포커스 이동 금지 — WCAG 3.2.1/3.2.2 계열).
  describe('N-1 — 툴바 버튼 포커스 중 Space', () => {
    function ToolbarHarness() {
      const activeTool = useLabelStore((s) => s.activeTool);
      const setActiveTool = useLabelStore((s) => s.setActiveTool);
      return (
        <>
          <button
            type="button"
            aria-label="바운딩 박스"
            aria-pressed={activeTool === ToolType.BBOX}
            onClick={() => setActiveTool(ToolType.BBOX)}
          >
            bbox
          </button>
          <CanvasShell frame={frame} width={800} height={600} labels={[]} />
        </>
      );
    }

    it('툴바_버튼_포커스_중_Space는_팬을_켜지_않고_버튼_활성화로_간다', async () => {
      const user = userEvent.setup();
      render(<ToolbarHarness />);
      const toolButton = screen.getByRole('button', { name: '바운딩 박스' });

      await user.click(toolButton);
      // 활성화가 포커스를 훔치지 않는다 — 클릭 후에도 포커스는 그 버튼에 남는다.
      expect(document.activeElement).toBe(toolButton);

      await user.keyboard(' ');

      // 팬 홀드는 걸리지 않는다(커서 불변) — 대신 Space 가 버튼을 다시 활성화한다.
      expect(screen.getByTestId('canvas-shell').style.cursor).toBe('');
      expect(useLabelStore.getState().activeTool).toBe(ToolType.BBOX);
    });

    it('포커스가_버튼을_떠나면_Space_팬이_다시_동작한다', async () => {
      // 해소 경로 — 캔버스를 한 번 클릭(또는 blur)하면 팬이 정상 복귀한다.
      const user = userEvent.setup();
      render(<ToolbarHarness />);
      const toolButton = screen.getByRole('button', { name: '바운딩 박스' });
      await user.click(toolButton);

      act(() => toolButton.blur());
      fireEvent.keyDown(window, { code: 'Space', key: ' ' });

      expect(screen.getByTestId('canvas-shell').style.cursor).toBe('grab');
    });
  });
});
