// 회귀 가드 — 검수 썸네일 스트립의 선택 테두리가 첫/마지막 항목에서 잘리지 않는다. [@design SCREEN-019]
//
// 결함(브라우저 실측): 검수 상세(/review/:id)에서 **1번 썸네일을 고르면 선택 표시인 파란 사각
//   테두리의 왼쪽이 잘려** 보였다.
//
// 기전: 현재 프레임 스타일 `FRAME_STATUS_BORDER.CURRENT` 가 `scale-105` 로 썸네일을 5% 확대해
//   강조를 **경계 바깥**에 그리는데, 그 확대분이 가로 스크롤 컨테이너(`overflow-x-auto`)의
//   클리핑 박스를 넘는다. 작업자 필름스트립(FrameFilmstrip)은 같은 상황에서 `px-2 py-1` 로
//   그만큼을 흡수하고 있었고, 검수 스트립만 가로 여백이 0(`py-0.5`)이라 잘렸다.
//
// ⚠ 이 가드는 **클래스**를 본다 — jsdom 에는 레이아웃이 없어 실제 잘림을 관측할 수 없다.
//   대신 잘림을 만드는 두 조건(확대 강조 · 클리핑 컨테이너)과 그것을 상쇄하는 여백을 함께
//   고정해, 어느 한쪽만 바뀌어도 RED 가 되게 한다.
//
// ⚠ 「확대를 없애서 고친다」는 오답이라 그 방향도 함께 막는다 — `FRAME_STATUS_BORDER` 는
//   라벨링 화면(FrameFilmstrip)과 **공유하는 표**라, `scale-105` 를 빼면 작업자 화면의 현재
//   프레임 강조까지 함께 약해진다.

import { beforeAll, describe, expect, it, vi } from 'vitest';
import { screen } from '@testing-library/react';

import { FRAME_STATUS_BORDER } from '@/features/label/components/frameStatus';
import { renderWithProviders } from '@/test/renderWithProviders';

import { FrameTimeline } from '../components/FrameTimeline';
import type { FrameDetail } from '../types';

// jsdom 에 scrollIntoView 미정의 — noop polyfill (기존 FrameTimeline 테스트와 동일).
beforeAll(() => {
  Element.prototype.scrollIntoView = vi.fn();
});

function frame(srcSn: number): FrameDetail {
  return { srcSn, frameNo: srcSn, imageUrl: `/frames/${srcSn}.jpg`, labels: [] };
}

const FRAMES = [frame(1), frame(2), frame(3)];

function classesOf(el: HTMLElement): string[] {
  return el.className.split(/\s+/).filter(Boolean);
}

describe('FrameTimeline 선택 테두리 잘림 방지', () => {
  it('스크롤_컨테이너가_가로세로_여백을_갖는다', () => {
    renderWithProviders(
      <FrameTimeline frames={FRAMES} currentFrameIdx={0} onSelect={() => {}} />,
    );

    const strip = screen.getByTestId('frame-timeline-strip');
    const classes = classesOf(strip);

    // 잘림을 만드는 조건 — 이 컨테이너가 자식을 잘라낸다.
    expect(classes).toContain('overflow-x-auto');
    // 그것을 상쇄하는 여백. 값은 작업자 필름스트립과 같은 기준(px-2 py-1)이다.
    expect(classes).toContain('px-2');
    expect(classes).toContain('py-1');
    // 구 상태(가로 0)로의 복귀를 직접 막는다.
    expect(classes).not.toContain('py-0.5');
  });

  it('여백은_첫_항목이_아니라_컨테이너에_있다', () => {
    // ⚠ 첫 항목(자식)에만 여백을 주면 마지막 항목이 그대로 잘리고 항목 간격도 첫 칸만 달라진다.
    renderWithProviders(
      <FrameTimeline frames={FRAMES} currentFrameIdx={0} onSelect={() => {}} />,
    );

    const first = screen.getByTestId('frame-timeline-thumb-0');
    const last = screen.getByTestId(`frame-timeline-thumb-${FRAMES.length - 1}`);

    // 썸네일 버튼은 자기 안쪽 여백(p-0.5)만 갖고 좌우 보정 여백을 갖지 않는다 —
    // 보정은 컨테이너 한 곳의 책임이라 첫/마지막이 대칭이다.
    for (const el of [first, last]) {
      expect(classesOf(el).some((c) => /^(pl|pr|px|ml|mr|mx)-/.test(c))).toBe(false);
    }
  });

  it('첫_항목과_마지막_항목이_선택돼도_같은_컨테이너_여백_안에서_그려진다', () => {
    // 선택 위치가 바뀌어도 여백은 컨테이너가 갖고 있으므로 양 끝이 동일하게 보호된다.
    for (const idx of [0, FRAMES.length - 1]) {
      const { unmount } = renderWithProviders(
        <FrameTimeline frames={FRAMES} currentFrameIdx={idx} onSelect={() => {}} />,
      );

      const selected = screen.getByTestId(`frame-timeline-thumb-${idx}`);
      // 선택 항목은 확대 강조를 갖는다(= 경계 바깥으로 삐져나온다).
      expect(classesOf(selected)).toContain('scale-105');

      const classes = classesOf(screen.getByTestId('frame-timeline-strip'));
      expect(classes).toContain('px-2');
      expect(classes).toContain('py-1');

      unmount();
    }
  });

  it('현재_프레임_강조는_확대를_유지한다_공유표를_약화시켜_고치지_않는다', () => {
    // `FRAME_STATUS_BORDER` 는 라벨링 화면(FrameFilmstrip)과 공유하는 표다 — 여기서 scale 을
    // 빼면 검수 잘림은 사라지지만 작업자 화면의 현재 프레임 강조가 함께 약해진다.
    expect(FRAME_STATUS_BORDER.CURRENT).toContain('scale-105');
    expect(FRAME_STATUS_BORDER.CURRENT).toContain('border-primary-500');
  });
});
