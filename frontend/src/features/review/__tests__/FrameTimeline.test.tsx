// SCR-REVIEW-002 Phase 5 — FrameTimeline 테스트.

import { beforeAll, describe, expect, it, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { FrameTimeline } from '../components/FrameTimeline';
import type { FrameDetail } from '../types';

// jsdom 에 scrollIntoView 미정의 — noop polyfill (테스트 안정성).
beforeAll(() => {
  Element.prototype.scrollIntoView = vi.fn();
});

function makeFrame(srcSn: number, frameNo: number): FrameDetail {
  return {
    srcSn,
    frameNo,
    imageUrl: `/frames/${srcSn}.jpg`,
    labels: [],
  };
}

function makeFrames(n: number): FrameDetail[] {
  return Array.from({ length: n }, (_, i) => makeFrame(1000 + i, i));
}

describe('FrameTimeline', () => {
  it('FrameTimeline_썸네일_N개_렌더', () => {
    const frames = makeFrames(5);
    render(
      <FrameTimeline frames={frames} currentFrameIdx={0} onSelect={() => {}} />,
    );

    for (let i = 0; i < 5; i += 1) {
      expect(
        screen.getByTestId(`frame-timeline-thumb-${i}`),
      ).toBeInTheDocument();
    }
  });

  it('FrameTimeline_현재_프레임_aria_current_true', () => {
    const frames = makeFrames(3);
    render(
      <FrameTimeline frames={frames} currentFrameIdx={1} onSelect={() => {}} />,
    );

    const current = screen.getByTestId('frame-timeline-thumb-1');
    expect(current).toHaveAttribute('aria-current', 'true');

    const other = screen.getByTestId('frame-timeline-thumb-0');
    expect(other).not.toHaveAttribute('aria-current');
  });

  it('FrameTimeline_썸네일_클릭_시_onSelect_호출', async () => {
    const user = userEvent.setup();
    const frames = makeFrames(4);
    const onSelect = vi.fn();
    render(
      <FrameTimeline frames={frames} currentFrameIdx={0} onSelect={onSelect} />,
    );

    await user.click(screen.getByTestId('frame-timeline-thumb-2'));
    expect(onSelect).toHaveBeenCalledWith(2);
  });

  it('FrameTimeline_진행률_텍스트_x_슬래시_y_표시', () => {
    const frames = makeFrames(60);
    render(
      <FrameTimeline frames={frames} currentFrameIdx={0} onSelect={() => {}} />,
    );

    const counter = screen.getByTestId('frame-timeline-counter');
    // "1 / 60 00:00"
    expect(counter).toHaveTextContent('1 / 60');
    expect(counter).toHaveTextContent('00:00');
  });

  it('FrameTimeline_진행바_width_퍼센트', () => {
    const frames = makeFrames(10);
    render(
      <FrameTimeline frames={frames} currentFrameIdx={4} onSelect={() => {}} />,
    );

    // (4 + 1) / 10 * 100 = 50%
    const bar = screen.getByTestId('frame-timeline-progress-bar');
    expect(bar).toHaveStyle({ width: '50%' });

    const progress = screen.getByTestId('frame-timeline-progress');
    expect(progress).toHaveAttribute('aria-valuenow', '5');
    expect(progress).toHaveAttribute('aria-valuemax', '10');
  });

  it('FrameTimeline_빈_frames_시_안내문', () => {
    render(<FrameTimeline frames={[]} currentFrameIdx={0} onSelect={() => {}} />);

    expect(screen.getByTestId('frame-timeline-empty')).toBeInTheDocument();
    expect(screen.queryByTestId('frame-timeline-strip')).toBeNull();
  });

  it('FrameTimeline_lazy_loading_속성_확인', () => {
    const frames = makeFrames(2);
    render(
      <FrameTimeline frames={frames} currentFrameIdx={0} onSelect={() => {}} />,
    );

    const thumb = screen.getByTestId('frame-timeline-thumb-0');
    const img = within(thumb).getByRole('img');
    expect(img).toHaveAttribute('loading', 'lazy');
    expect(img).toHaveAttribute('width', '64');
    expect(img).toHaveAttribute('height', '40');
  });

  it('FrameTimeline_arrow_key_네비게이션', async () => {
    const user = userEvent.setup();
    const frames = makeFrames(5);
    const onSelect = vi.fn();
    render(
      <FrameTimeline frames={frames} currentFrameIdx={2} onSelect={onSelect} />,
    );

    const region = screen.getByTestId('frame-timeline');
    region.focus();

    await user.keyboard('{ArrowRight}');
    expect(onSelect).toHaveBeenCalledWith(3);

    onSelect.mockClear();
    await user.keyboard('{ArrowLeft}');
    expect(onSelect).toHaveBeenCalledWith(1);
  });

  it('FrameTimeline_경계값_idx_범위_clamp', () => {
    const frames = makeFrames(3);
    render(
      <FrameTimeline frames={frames} currentFrameIdx={10} onSelect={() => {}} />,
    );
    // 범위 초과 시 최대 idx 로 clamp — "3 / 3"
    const counter = screen.getByTestId('frame-timeline-counter');
    expect(counter).toHaveTextContent('3 / 3');
  });
});
