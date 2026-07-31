// Phase 3 — 진행 오버레이(BusyOverlay).
//
// 사용자 원 요청("되고 있는지 인지가 안 되는 상태에서 막 하고 있는 것 같다")의 핵심 해결책이다.
// 무엇이 진행 중인지 캔버스 위에 보이고, 거기서 바로 취소할 수 있어야 한다.
//
// 고정하는 것:
//  - 지연 표시(AC7): 즉시 그리기처럼 짧은 작업에는 매 클릭 깜빡이지 않는다.
//  - 문구(R6): 모델명(YOLO/SAM/SAM2)을 노출하지 않는다.
//  - 백드롭이 pointer 이벤트를 흡수해 시각적 차단과 물리적 차단이 일치한다.
//  - a11y: role=status / aria-live / aria-busy + 취소 버튼 포커스 + ESC 취소.
//  - 타이머 누수 없음(지연 표시·경과 시간 타이머는 언마운트 시 정리).

import { afterEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen } from '@testing-library/react';

import type { BusyKind } from '@/stores/useLabelStore';

import { BusyOverlay, BUSY_OVERLAY_DELAY_MS } from '../BusyOverlay';

/** 지연 표시 창을 넘겨 오버레이를 노출시킨다(테스트 의도를 한 줄로 드러내는 헬퍼). */
function advancePastDelay(extraMs = 0) {
  act(() => {
    vi.advanceTimersByTime(BUSY_OVERLAY_DELAY_MS + 1 + extraMs);
  });
}

afterEach(() => {
  vi.useRealTimers();
});

describe('BusyOverlay — 진행 표시 + 취소', () => {
  it('busy가_없으면_오버레이가_렌더되지_않는다', () => {
    vi.useFakeTimers();
    render(<BusyOverlay kind={null} startedAt={Date.now()} onCancel={vi.fn()} />);
    advancePastDelay();
    expect(screen.queryByTestId('busy-overlay')).not.toBeInTheDocument();
  });

  it('작업_종류별로_올바른_문구가_표시된다_모델명은_없다', () => {
    const expected: [BusyKind, string][] = [
      ['AI_DETECT', 'AI 탐지 진행 중'],
      ['AI_SEGMENT', 'AI 분할 진행 중'],
      ['AI_TRACK', 'AI 추적 진행 중'],
      ['SAVE', '저장 중'],
      ['LOAD', '불러오는 중'],
    ];
    for (const [kind, label] of expected) {
      vi.useFakeTimers();
      const { unmount } = render(
        <BusyOverlay kind={kind} startedAt={Date.now()} onCancel={vi.fn()} />,
      );
      advancePastDelay();
      const overlay = screen.getByTestId('busy-overlay');
      expect(overlay.textContent).toContain(label);
      // R6 — 사용자 문구에 모델명 금지.
      expect(overlay.textContent ?? '').not.toMatch(/YOLO|SAM/i);
      unmount();
      vi.useRealTimers();
    }
  });

  it('300ms_미만_짧은_작업에는_오버레이가_뜨지_않는다', () => {
    // AC7 — 즉시 그리기는 클릭마다 짧은 busy 가 생긴다. 깜빡이면 화면이 고장난 것처럼 보인다.
    vi.useFakeTimers();
    const { rerender } = render(
      <BusyOverlay kind="AI_SEGMENT" startedAt={Date.now()} onCancel={vi.fn()} />,
    );
    act(() => {
      vi.advanceTimersByTime(BUSY_OVERLAY_DELAY_MS - 1);
    });
    expect(screen.queryByTestId('busy-overlay')).not.toBeInTheDocument();

    // 작업이 지연 창 안에 끝났다 → 이후 시간이 흘러도 오버레이는 뜨지 않는다.
    rerender(<BusyOverlay kind={null} startedAt={undefined} onCancel={vi.fn()} />);
    advancePastDelay(2000);
    expect(screen.queryByTestId('busy-overlay')).not.toBeInTheDocument();
  });

  it('지연_창을_넘긴_작업은_오버레이가_노출된다', () => {
    vi.useFakeTimers();
    render(<BusyOverlay kind="AI_TRACK" startedAt={Date.now()} onCancel={vi.fn()} />);
    advancePastDelay();
    expect(screen.getByTestId('busy-overlay')).toBeInTheDocument();
  });

  it('취소_버튼_클릭시_cancelBusy가_호출되고_오버레이가_사라진다', () => {
    vi.useFakeTimers();
    const onCancel = vi.fn();
    const { rerender } = render(
      <BusyOverlay kind="SAVE" startedAt={Date.now()} onCancel={onCancel} />,
    );
    advancePastDelay();

    fireEvent.click(screen.getByRole('button', { name: '작업 취소' }));
    expect(onCancel).toHaveBeenCalledTimes(1);

    // 취소가 store busy 를 내리면(상위가 kind=null 로 갱신) 잔상 없이 사라진다.
    rerender(<BusyOverlay kind={null} startedAt={undefined} onCancel={onCancel} />);
    expect(screen.queryByTestId('busy-overlay')).not.toBeInTheDocument();
  });

  it('오버레이에서_ESC를_누르면_취소된다', () => {
    vi.useFakeTimers();
    const onCancel = vi.fn();
    render(<BusyOverlay kind="AI_DETECT" startedAt={Date.now()} onCancel={onCancel} />);
    advancePastDelay();

    fireEvent.keyDown(screen.getByTestId('busy-overlay'), { key: 'Escape' });
    expect(onCancel).toHaveBeenCalledTimes(1);
  });

  it('오버레이는_role_status와_aria_busy를_가진다', () => {
    vi.useFakeTimers();
    render(<BusyOverlay kind="AI_DETECT" startedAt={Date.now()} onCancel={vi.fn()} />);
    advancePastDelay();

    const overlay = screen.getByRole('status');
    expect(overlay).toHaveAttribute('aria-busy', 'true');
    expect(overlay).toHaveAttribute('aria-live', 'polite');
    expect(overlay).toBe(screen.getByTestId('busy-overlay'));
  });

  it('오버레이가_뜨면_취소_버튼으로_포커스가_이동한다', () => {
    vi.useFakeTimers();
    render(<BusyOverlay kind="SAVE" startedAt={Date.now()} onCancel={vi.fn()} />);
    advancePastDelay();
    expect(document.activeElement).toBe(screen.getByRole('button', { name: '작업 취소' }));
  });

  it('오버레이_백드롭이_캔버스_클릭을_흡수한다', () => {
    // 시각적 차단과 물리적 차단이 어긋나면 "막힌 것처럼 보이는데 눌리는" 화면이 된다.
    vi.useFakeTimers();
    const onCanvasClick = vi.fn();
    const onCanvasMouseDown = vi.fn();
    // 캔버스 컨테이너 역할 — 오버레이 위 이벤트가 아래로(상위로) 새는지 본다.
    const { container } = render(
      <BusyOverlay kind="AI_SEGMENT" startedAt={Date.now()} onCancel={vi.fn()} />,
    );
    container.addEventListener('click', onCanvasClick);
    container.addEventListener('mousedown', onCanvasMouseDown);
    advancePastDelay();

    const overlay = screen.getByTestId('busy-overlay');
    fireEvent.mouseDown(overlay);
    fireEvent.mouseUp(overlay);
    fireEvent.click(overlay);

    expect(onCanvasMouseDown).not.toHaveBeenCalled();
    expect(onCanvasClick).not.toHaveBeenCalled();
  });

  it('모달이_열려있으면_오버레이가_포커스를_가져가지_않는다', () => {
    // D2 — 오버레이(z-20)는 모달(z-50 portal) **뒤**에 있어 보이지 않는다. 그 보이지 않는
    // 취소 버튼이 포커스를 가져가면 ①모달 Tab 트랩이 무력화되고 ②Enter/Space 로 보이지 않는
    // "작업 취소" 가 눌려 저장이 폐기된다.
    vi.useFakeTimers();
    const dialog = document.createElement('div');
    dialog.setAttribute('role', 'dialog');
    dialog.setAttribute('aria-modal', 'true');
    const inModal = document.createElement('button');
    inModal.textContent = '저장 후 이동';
    dialog.appendChild(inModal);
    document.body.appendChild(dialog);
    inModal.focus();

    try {
      render(<BusyOverlay kind="SAVE" startedAt={Date.now()} onCancel={vi.fn()} />);
      advancePastDelay();

      expect(document.activeElement).toBe(inModal);
      expect(document.activeElement).not.toBe(
        screen.getByRole('button', { name: '작업 취소' }),
      );
    } finally {
      document.body.removeChild(dialog);
    }
  });

  it('경과_초는_스크린리더_라이브_리전에서_제외된다', () => {
    // D5(WCAG) — role=status/aria-live 리전 안의 경과 초는 매초 갱신돼 최대 300회 낭독된다.
    // 작업명은 계속 낭독되어야 하므로 경과 초만 라이브 리전에서 분리한다.
    vi.useFakeTimers();
    render(<BusyOverlay kind="AI_TRACK" startedAt={Date.now()} onCancel={vi.fn()} />);
    advancePastDelay();

    const elapsed = screen.getByTestId('busy-overlay-elapsed');
    expect(elapsed).toHaveAttribute('aria-hidden', 'true');
    // 작업명은 라이브 리전에 남아 있어야 한다(무엇이 진행 중인지 못 듣게 되면 안 된다).
    expect(screen.getByTestId('busy-overlay').textContent).toContain('AI 추적 진행 중');
  });

  it('경과_시간이_표시된다', () => {
    vi.useFakeTimers();
    const startedAt = Date.now();
    render(<BusyOverlay kind="AI_TRACK" startedAt={startedAt} onCancel={vi.fn()} />);
    advancePastDelay();
    expect(screen.getByTestId('busy-overlay-elapsed').textContent).toContain('0초');

    act(() => {
      vi.advanceTimersByTime(2000);
    });
    expect(screen.getByTestId('busy-overlay-elapsed').textContent).toContain('2초');
  });

  it('언마운트하면_지연표시_경과시간_타이머가_남지_않는다', () => {
    vi.useFakeTimers();
    const { unmount } = render(
      <BusyOverlay kind="SAVE" startedAt={Date.now()} onCancel={vi.fn()} />,
    );
    advancePastDelay();
    expect(vi.getTimerCount()).toBeGreaterThan(0); // 경과 시간 인터벌 동작 중

    unmount();
    // 인터벌이 남아 있으면 시간이 흘러도 스스로 재예약돼 카운트가 0 이 되지 않는다.
    act(() => {
      vi.advanceTimersByTime(5000);
    });
    expect(vi.getTimerCount()).toBe(0);
  });
});
