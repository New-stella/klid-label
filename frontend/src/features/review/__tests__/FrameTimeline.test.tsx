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
    // "1 / 60" — 확정 디자인의 `.rv-filmstrip-progress` 표기.
    expect(counter).toHaveTextContent('1 / 60');
    // 구 구현이 덧붙이던 가상 타임코드("00:00")는 디자인에 없다. 실제 FPS 가 아니라 인덱스를
    // 1fps 로 가정해 환산한 표시값이라 카운터가 이미 같은 사실을 담고 있었다.
    expect(counter.textContent).not.toMatch(/\d{2}:\d{2}/);
  });

  it('FrameTimeline_진행_상태는_막대가_아니라_카운터가_담는다', () => {
    const frames = makeFrames(10);
    render(
      <FrameTimeline frames={frames} currentFrameIdx={4} onSelect={() => {}} />,
    );

    // 디자인에 없는 진행률 막대(머리말 줄 구성물)는 두지 않는다.
    expect(screen.queryByTestId('frame-timeline-progress-bar')).toBeNull();

    // `role="progressbar"` 계약은 카운터가 그대로 이어받는다(a11y 손실 없음).
    const counter = screen.getByTestId('frame-timeline-counter');
    expect(counter).toHaveAttribute('role', 'progressbar');
    expect(counter).toHaveAttribute('aria-valuenow', '5');
    expect(counter).toHaveAttribute('aria-valuemax', '10');
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

  // ── 접기/펼치기 (SCREEN-019 §프레임 썸네일 스트립) ──────────────────
  describe('접기/펼치기', () => {
    it('FrameTimeline_기본은_펼침이고_토글에_aria_expanded_true', () => {
      render(
        <FrameTimeline frames={makeFrames(3)} currentFrameIdx={0} onSelect={() => {}} />,
      );

      expect(screen.getByTestId('frame-timeline-toggle')).toHaveAttribute(
        'aria-expanded',
        'true',
      );
      expect(screen.getByTestId('frame-timeline-strip')).toBeInTheDocument();
    });

    it('FrameTimeline_접으면_썸네일_목록이_DOM_에서_제거된다', async () => {
      const user = userEvent.setup();
      render(
        <FrameTimeline frames={makeFrames(3)} currentFrameIdx={0} onSelect={() => {}} />,
      );

      await user.click(screen.getByTestId('frame-timeline-toggle'));

      expect(screen.getByTestId('frame-timeline-toggle')).toHaveAttribute(
        'aria-expanded',
        'false',
      );
      // aria-hidden 이 아니라 제거 — 보이지 않는 버튼에 Tab 포커스가 갇히지 않아야 한다.
      expect(screen.queryByTestId('frame-timeline-strip')).toBeNull();
      expect(screen.queryByTestId('frame-timeline-thumb-0')).toBeNull();
    });

    it('FrameTimeline_접혀도_진행률과_카운터는_남는다', async () => {
      const user = userEvent.setup();
      render(
        <FrameTimeline frames={makeFrames(10)} currentFrameIdx={4} onSelect={() => {}} />,
      );

      await user.click(screen.getByTestId('frame-timeline-toggle'));

      const counter = screen.getByTestId('frame-timeline-counter');
      expect(counter).toHaveTextContent('5 / 10');
      expect(counter).toHaveAttribute('aria-valuenow', '5');
      // 다시 펼칠 수단이 남아 있어야 한다.
      expect(screen.getByTestId('frame-timeline-toggle')).toBeVisible();
    });

    it('FrameTimeline_접었다_펴면_썸네일과_현재표시가_되돌아온다', async () => {
      const user = userEvent.setup();
      render(
        <FrameTimeline frames={makeFrames(3)} currentFrameIdx={1} onSelect={() => {}} />,
      );

      const toggle = screen.getByTestId('frame-timeline-toggle');
      await user.click(toggle);
      await user.click(toggle);

      expect(toggle).toHaveAttribute('aria-expanded', 'true');
      expect(screen.getByTestId('frame-timeline-thumb-1')).toHaveAttribute(
        'aria-current',
        'true',
      );
    });

    it('FrameTimeline_접힌_상태에서도_toolbar_계약과_방향키_이동이_유지된다', async () => {
      const user = userEvent.setup();
      const onSelect = vi.fn();
      render(
        <FrameTimeline frames={makeFrames(5)} currentFrameIdx={2} onSelect={onSelect} />,
      );

      await user.click(screen.getByTestId('frame-timeline-toggle'));

      const region = screen.getByTestId('frame-timeline');
      expect(region).toHaveAttribute('role', 'toolbar');
      expect(region).toHaveAttribute('aria-orientation', 'horizontal');

      region.focus();
      await user.keyboard('{ArrowRight}');
      expect(onSelect).toHaveBeenCalledWith(3);
    });

    it('FrameTimeline_토글은_toolbar_바깥에_있어_방향키가_토글을_삼키지_않는다', async () => {
      // given — toolbar 안에서 ←/→ 는 항목 사이 포커스 이동에 쓰이는 키인데 이 툴바는 그 키를
      //         프레임 이동에 쓴다. 토글이 툴바 안에 있으면 토글에 포커스가 있을 때 방향키가
      //         포커스를 옮기는 대신 프레임을 바꿔 툴바 키보드 관례와 어긋난다.
      const user = userEvent.setup();
      const onSelect = vi.fn();
      render(
        <FrameTimeline frames={makeFrames(5)} currentFrameIdx={2} onSelect={onSelect} />,
      );

      const toolbar = screen.getByTestId('frame-timeline');
      const toggle = screen.getByTestId('frame-timeline-toggle');

      // then — 토글은 툴바의 자손이 아니다(썸네일만 툴바에 담긴다).
      expect(toolbar).toHaveAttribute('role', 'toolbar');
      expect(toolbar.contains(toggle)).toBe(false);
      expect(toolbar.contains(screen.getByTestId('frame-timeline-thumb-0'))).toBe(true);

      // then — 토글에 포커스가 있을 때 방향키가 프레임을 바꾸지 않는다.
      toggle.focus();
      await user.keyboard('{ArrowRight}');
      expect(onSelect).not.toHaveBeenCalled();
    });

    it('FrameTimeline_빈_frames_면_접기_토글을_두지_않는다', () => {
      render(<FrameTimeline frames={[]} currentFrameIdx={0} onSelect={() => {}} />);

      expect(screen.getByTestId('frame-timeline-empty')).toBeInTheDocument();
      expect(screen.queryByTestId('frame-timeline-toggle')).toBeNull();
    });
  });

  // ── R1 (검수 프레임 상태색) — resolveFrameStatus 재사용. 미해소이슈·저장·현재만 반영.
  // v2 반려는 영상 단위(REJECTION.srcSn=null)라 프레임색(주황) 미대상 — 테스트도 미포함.
  describe('R1 프레임 상태색', () => {
    it('검수_프레임에_미해소이슈면_빨강_테두리', () => {
      const frames = makeFrames(3);
      render(
        <FrameTimeline
          frames={frames}
          currentFrameIdx={0}
          onSelect={() => {}}
          inquirySrcSns={new Set([1002])}
        />,
      );
      expect(
        screen.getByTestId('frame-timeline-thumb-2').className,
      ).toContain('border-red-500');
    });

    it('검수_프레임에_저장라벨이면_연두_테두리', () => {
      const frames = makeFrames(3);
      render(
        <FrameTimeline
          frames={frames}
          currentFrameIdx={0}
          onSelect={() => {}}
          savedSrcSns={new Set([1001])}
        />,
      );
      expect(
        screen.getByTestId('frame-timeline-thumb-1').className,
      ).toContain('border-green-400');
    });

    it('현재_프레임이_미해소이슈여도_현재강조가_우선', () => {
      const frames = makeFrames(3);
      render(
        <FrameTimeline
          frames={frames}
          currentFrameIdx={1}
          onSelect={() => {}}
          inquirySrcSns={new Set([1001])}
        />,
      );
      const thumb = screen.getByTestId('frame-timeline-thumb-1').className;
      expect(thumb).toContain('border-primary-500');
      expect(thumb).not.toContain('border-red-500');
    });

    it('이슈없으면_기본_테두리_무회귀', () => {
      const frames = makeFrames(3);
      render(
        <FrameTimeline frames={frames} currentFrameIdx={0} onSelect={() => {}} />,
      );
      // 현재 아닌 일반 프레임(idx1) → 기본(transparent). 기존 동작 유지.
      const thumb = screen.getByTestId('frame-timeline-thumb-1').className;
      expect(thumb).toContain('border-transparent');
      expect(thumb).not.toContain('border-red-500');
      expect(thumb).not.toContain('border-green-400');
    });
  });
});
