// 진행 오버레이 — **경과 시간 옆에 그 실행의 대기 상한**을 함께 보여준다.
//
// 왜 필요한가: 대기가 최대 4분까지 늘어나면 "몇 초 경과" 만으로는 **끝을 가늠할 수 없다**.
// 사용자는 언제까지 기다려야 하는지 모른 채 취소할지 말지를 정해야 한다. 상한이 서버에서
// 오게 됐으므로(이 라운드) 이제 화면이 그 끝을 말할 수 있다.
//
// ⚠ 여기서 만들지 않는 것: **프레임 단위 실시간 퍼센트**. 서버에 진행을 알려 주는 통로가 하나도
//   없어 지금 데이터로는 만들 수 없고, 지어내면 거짓 표시가 된다. 프레임을 훑는 작업의 진행은
//   «나눠 보낸 조각» 단위로만 갱신된다(추적 도구의 진행률 막대).

import { afterEach, describe, expect, it, vi } from 'vitest';
import { act, render, screen } from '@testing-library/react';

import { BusyOverlay, BUSY_OVERLAY_DELAY_MS } from '../BusyOverlay';

function advancePastDelay(extraMs = 0) {
  act(() => {
    vi.advanceTimersByTime(BUSY_OVERLAY_DELAY_MS + 1 + extraMs);
  });
}

afterEach(() => {
  vi.useRealTimers();
});

describe('BusyOverlay — 대기 상한 표시', () => {
  it('경과_시간과_이_실행의_대기_상한을_함께_보여준다', () => {
    vi.useFakeTimers();
    render(
      <BusyOverlay kind="AI_DETECT" startedAt={Date.now()} limitMs={240_000} onCancel={vi.fn()} />,
    );
    advancePastDelay();

    // 경과는 이미 있던 표시다 — 여기에 «끝» 이 더해진다.
    expect(screen.getByTestId('busy-overlay-elapsed')).toHaveTextContent('0초 / 최대 240초');
  });

  it('경과_시간이_흐르면_같은_상한_옆에서_올라간다', () => {
    vi.useFakeTimers();
    render(
      <BusyOverlay kind="AI_TRACK" startedAt={Date.now()} limitMs={90_000} onCancel={vi.fn()} />,
    );
    advancePastDelay();
    act(() => {
      vi.advanceTimersByTime(3_000);
    });

    expect(screen.getByTestId('busy-overlay-elapsed')).toHaveTextContent('3초 / 최대 90초');
  });

  it('작업마다_다른_상한을_보여준다', () => {
    // 공용 상한 하나를 나눈 것이 이 라운드다 — 화면도 그 종류의 값을 보여야 의미가 있다.
    vi.useFakeTimers();
    const { unmount } = render(
      <BusyOverlay kind="AI_DETECT" startedAt={Date.now()} limitMs={60_000} onCancel={vi.fn()} />,
    );
    advancePastDelay();
    expect(screen.getByTestId('busy-overlay-elapsed')).toHaveTextContent('최대 60초');
    unmount();

    render(
      <BusyOverlay
        kind="AI_AUTO_TRACK"
        startedAt={Date.now()}
        limitMs={300_000}
        onCancel={vi.fn()}
      />,
    );
    advancePastDelay();
    expect(screen.getByTestId('busy-overlay-elapsed')).toHaveTextContent('최대 300초');
  });

  it('상한을_모르면_경과만_보여주고_거짓_끝을_지어내지_않는다', () => {
    vi.useFakeTimers();
    render(<BusyOverlay kind="SAVE" startedAt={Date.now()} onCancel={vi.fn()} />);
    advancePastDelay();

    const elapsed = screen.getByTestId('busy-overlay-elapsed');
    expect(elapsed).toHaveTextContent('0초 경과');
    expect(elapsed.textContent).not.toContain('최대');
  });

  it('취소_안내는_요청을_중단한다고_말한다', () => {
    // 취소가 실제로 서버 요청을 끊게 됐다 — «서버 처리가 즉시 중단되지 않는다» 는 옛 안내는
    // 이제 사실과 다르고, 사용자가 취소를 주저하게 만든다.
    vi.useFakeTimers();
    render(<BusyOverlay kind="AI_DETECT" startedAt={Date.now()} onCancel={vi.fn()} />);
    advancePastDelay();

    const panel = screen.getByTestId('busy-overlay');
    expect(panel).toHaveTextContent('요청을 중단');
    expect(panel.textContent).not.toContain('서버 처리가 즉시 중단되지는 않습니다');
  });
});
