// TC-FE-306 회귀 가드 — 좌측 도구바 하단 버튼 잘림(구 미해결 결함) 해소 계약.
//
// 배경: 도구바는 `overflow-hidden` 조상(LabelingPage 의 `flex flex-1 overflow-hidden`) 안의 flex
// 아이템인데 자체 스크롤 계약이 없어, 짧은 뷰포트에서 하단 버튼이 잘리고 **영구히 클릭 불가**였다.
//
// ⚠ jsdom 은 레이아웃을 계산하지 않는다(getBoundingClientRect 전부 0) — 실제 잘림을 폭·높이로
//   단언하면 항상 통과하는 **거짓 가드**가 된다. 그래서 여기서는 잘림을 막는 **구조 계약**만 고정한다:
//   ① 버튼 목록이 스크롤 컨테이너다 ② 마지막 버튼이 그 안에 있다 ③ 툴팁은 그 **밖**(body portal)에
//   그려져 overflow 에 함께 잘리지 않는다.
//   ③ 이 계약인 이유: overflow-y 를 non-visible 로 두면 overflow-x 도 auto 로 강제되므로,
//   툴팁이 상자 안에 있으면 스크롤을 얻는 대가로 툴팁이 잘리는 회귀가 생긴다.
//
// ★저장 버튼은 더 이상 이 도구바에 없다 — 캔버스 상단 옵션바로 이관됐다(SCREEN-005 확정).
//   따라서 이 파일의 잘림 계약은 도구바의 **마지막 버튼(화면 맞춤)** 으로 고정하고, 저장 버튼의
//   잠금·진행 계약은 CanvasOptionBar.test.tsx 가 이어받는다.

import { fireEvent, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { useLabelStore } from '@/stores/useLabelStore';
import { renderWithProviders } from '@/test/renderWithProviders';

import { ToolBar } from '../components/ToolBar';

function scrollBox(): HTMLElement {
  return screen.getByTestId('label-toolbar-scroll');
}

describe('ToolBar — 스크롤 계약(TC-FE-306)', () => {
  beforeEach(() => {
    useLabelStore.getState().reset();
  });

  it('버튼_목록이_자체_스크롤_계약을_가진다_조상이_자르지_않는다', () => {
    renderWithProviders(<ToolBar onAutolabel={vi.fn()} />);

    const cls = scrollBox().className;
    // 넘치면 이 안에서 스크롤한다.
    expect(cls).toContain('overflow-y-auto');
    // flex 자동 최소 크기를 풀어야 overflow 가 실제로 발동한다(없으면 콘텐츠 높이가 하한).
    expect(cls).toContain('min-h-0');
    expect(cls).toContain('flex-1');
    // overflow-y 가 non-visible 이면 overflow-x 도 auto 로 강제된다 — 가로 스크롤바를 막는다.
    expect(cls).toContain('overflow-x-hidden');
  });

  it('마지막_버튼은_스크롤_컨테이너_안에_있어_넘쳐도_스크롤로_도달한다', () => {
    renderWithProviders(<ToolBar onAutolabel={vi.fn()} />);

    const last = screen.getByTestId('label-toolbar-fit');
    expect(scrollBox().contains(last)).toBe(true);
    // 목록의 마지막 버튼이라 가장 먼저 잘리던 자리다 — 위치 자체를 고정한다.
    const buttons = Array.from(scrollBox().querySelectorAll('button'));
    expect(buttons[buttons.length - 1]).toBe(last);
  });

  it('★툴팁은_스크롤_상자_밖_body_로_portal_되어_함께_잘리지_않는다', () => {
    renderWithProviders(<ToolBar />);

    // when: 마지막(화면 맞춤) 버튼에 호버
    fireEvent.mouseOver(screen.getByTestId('label-toolbar-fit'));

    // then: 툴팁이 뜨되 스크롤 상자(overflow 클리핑 영역) 안이 아니다.
    const tip = screen.getByTestId('label-toolbar-tooltip');
    expect(tip.textContent).toContain('화면 맞춤');
    expect(scrollBox().contains(tip)).toBe(false);
    expect(document.body.contains(tip)).toBe(true);
    // 위치는 뷰포트 좌표계여야 상자 밖에서도 버튼 옆에 붙는다.
    expect(tip.className).toContain('fixed');
  });

  it('호버가_끝나면_툴팁이_사라진다', () => {
    renderWithProviders(<ToolBar />);
    const fit = screen.getByTestId('label-toolbar-fit');

    fireEvent.mouseOver(fit);
    expect(screen.getByTestId('label-toolbar-tooltip')).toBeInTheDocument();

    fireEvent.mouseOut(fit);
    expect(screen.queryByTestId('label-toolbar-tooltip')).toBeNull();
  });

  it('키보드_포커스로도_툴팁이_뜬다_마우스_전용이_아니다', () => {
    renderWithProviders(<ToolBar />);
    const fit = screen.getByTestId('label-toolbar-fit');

    // ⚠ jsdom 에서 native `el.focus()` 는 activeElement 만 바꾸고 React 합성 onFocus 로 이어지지
    //   않는다(실측). 합성 이벤트 경로를 검증해야 하므로 fireEvent 를 쓴다.
    fireEvent.focus(fit);
    expect(screen.getByTestId('label-toolbar-tooltip').textContent).toContain('화면 맞춤');

    fireEvent.blur(fit);
    expect(screen.queryByTestId('label-toolbar-tooltip')).toBeNull();
  });

  it('스크롤하면_툴팁을_닫는다_좌표가_낡아_엉뚱한_곳에_남지_않게', () => {
    renderWithProviders(<ToolBar />);
    fireEvent.mouseOver(screen.getByTestId('label-toolbar-fit'));
    expect(screen.getByTestId('label-toolbar-tooltip')).toBeInTheDocument();

    fireEvent.scroll(scrollBox());

    expect(screen.queryByTestId('label-toolbar-tooltip')).toBeNull();
  });
});

describe('ToolBar — 편집 액션은 도구바에 두지 않는다(SCREEN-005 확정)', () => {
  beforeEach(() => {
    useLabelStore.getState().reset();
  });

  it('★저장_삭제_실행취소_다시실행_버튼이_도구바에_없다', () => {
    // 사양: "삭제·실행취소·다시실행·저장은 좌측 도구바가 아니라 캔버스 상단 옵션바에 둔다."
    // 양쪽에 두면 잠금·진행중 판정이 한쪽만 갱신돼 조용히 열린 구멍이 생긴다.
    renderWithProviders(<ToolBar onAutolabel={vi.fn()} />);

    const toolbar = screen.getByRole('toolbar', { name: '라벨링 도구' });
    for (const name of ['저장', '삭제', '실행 취소', '다시 실행']) {
      expect(
        Array.from(toolbar.querySelectorAll('button')).map((b) => b.getAttribute('aria-label')),
      ).not.toContain(name);
    }
    expect(screen.queryByTestId('label-toolbar-save')).toBeNull();
  });

  it('보기_조작(화면_맞춤)과_그리기_도구는_도구바에_그대로_남는다', () => {
    // 이관 대상은 편집 액션 4종뿐이다 — 보기 조작·그리기 도구까지 옮기면 사양 위반이다.
    renderWithProviders(<ToolBar onAutolabel={vi.fn()} />);

    expect(screen.getByRole('button', { name: '화면 맞춤' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '선택' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'AI 탐지' })).toBeInTheDocument();
  });
});
