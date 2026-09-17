// B5#13 — 단축키 도움말의 표면이 **좌측 도구바 맨 아래 버튼 hover** 다(SCREEN-005).
//
// 검증 축:
//  ① 도구바에 도움말 트리거가 실재한다(사양 표면이 도달 가능하다)
//  ② hover 로 열린다(사양) — 그리고 **focus 로도 열린다**(hover 전용은 키보드 사용자에게
//     도달 불가라 접근성 회귀다, WCAG 2.1.1). 이 두 축은 짝이며 한쪽만 두면 안 된다.
//  ③ 표 내용은 SHORTCUT_KEYMAP 단일 출처에서 파생된다(도구바가 표기를 복제하지 않는다)
//  ④ 포털 모드에서는 미제공 도구 안내를 노출하지 않는다(모달 표면과 동일 정책)

import { fireEvent, screen, within } from '@testing-library/react';
import { beforeEach, describe, expect, it } from 'vitest';

import { useLabelStore } from '@/stores/useLabelStore';
import { renderWithProviders } from '@/test/renderWithProviders';

import { ToolBar } from '../components/ToolBar';

const HELP_BUTTON = 'label-toolbar-shortcut-help';
const HELP_PANEL = 'label-toolbar-shortcut-panel';

describe('ToolBar — 단축키 도움말 표면', () => {
  beforeEach(() => {
    useLabelStore.getState().reset();
  });

  it('도구바에_단축키_도움말_버튼이_있다', () => {
    renderWithProviders(<ToolBar />);
    expect(screen.getByTestId(HELP_BUTTON)).toBeInTheDocument();
    // 열기 전에는 패널이 없다.
    expect(screen.queryByTestId(HELP_PANEL)).toBeNull();
  });

  it('hover하면_단축키_표가_열린다', () => {
    renderWithProviders(<ToolBar />);
    fireEvent.mouseEnter(screen.getByTestId(HELP_BUTTON));

    expect(screen.getByTestId(HELP_PANEL)).toBeInTheDocument();
    // 내용은 SHORTCUT_KEYMAP 파생 — 도구바가 키 표기를 따로 들고 있지 않다.
    expect(screen.getByText('BBOX 도구')).toBeInTheDocument();
    expect(screen.getByText('저장')).toBeInTheDocument();
    expect(screen.getByText('Ctrl+S')).toBeInTheDocument();
  });

  it('★focus로도_열린다_hover전용은_키보드_사용자에게_도달_불가라_접근성_회귀다', () => {
    renderWithProviders(<ToolBar />);
    const button = screen.getByTestId(HELP_BUTTON);

    fireEvent.focus(button);

    expect(screen.getByTestId(HELP_PANEL)).toBeInTheDocument();
    expect(button).toHaveAttribute('aria-expanded', 'true');
  });

  it('포인터가_떠나면_닫힌다', () => {
    renderWithProviders(<ToolBar />);
    const button = screen.getByTestId(HELP_BUTTON);

    fireEvent.mouseEnter(button);
    expect(screen.getByTestId(HELP_PANEL)).toBeInTheDocument();

    fireEvent.mouseLeave(button);
    expect(screen.queryByTestId(HELP_PANEL)).toBeNull();
  });

  it('Esc로_닫을_수_있다_WCAG_1_4_13_dismissible', () => {
    renderWithProviders(<ToolBar />);
    fireEvent.focus(screen.getByTestId(HELP_BUTTON));
    expect(screen.getByTestId(HELP_PANEL)).toBeInTheDocument();

    fireEvent.keyDown(document, { key: 'Escape' });

    expect(screen.queryByTestId(HELP_PANEL)).toBeNull();
  });

  // ★뷰포트 상한 + 내부 스크롤 계약 (2026-08-08 실측 결함 회귀 가드).
  //   패널은 버튼 하단 기준으로 **위로만** 자라서 내용이 뷰포트보다 길면 상단이 잘렸고
  //   (1280x800 실측 top=-444.9 — 제목과 「도구」 섹션이 통째로 사라졌다), 스크롤 컨테이너가
  //   없어 잘린 내용에 도달할 방법이 없었다.
  // ⚠ jsdom 은 레이아웃이 없어 getBoundingClientRect 가 전부 0 이라 **잘림도 스크롤도 재현되지
  //   않는다** — 계약(위치 기준축·상한·overflow)이 걸려 있는지만 구조로 확인한다.
  it('★패널은_bottom_기준_고정_뷰포트_상한_내부스크롤_계약을_갖는다_jsdom_은_잘림_재현_못함', () => {
    renderWithProviders(<ToolBar />);
    fireEvent.mouseEnter(screen.getByTestId(HELP_BUTTON));

    const anchor = screen.getByTestId(HELP_PANEL);
    // top 기준 + translate 로 두면 내용이 길 때 화면 위로 넘친다 → bottom 기준이어야 한다.
    expect(anchor.style.bottom).not.toBe('');
    expect(anchor.style.top).toBe('');
    expect(anchor.className).not.toContain('-translate-y-full');

    const box = screen.getByTestId('label-toolbar-shortcut-panel-box');
    expect(box.className).toContain('overflow-y-auto');
    // 상한은 고정 px 이 아니라 뷰포트(100vh) 기준.
    expect(box.style.maxHeight).toContain('100vh');
  });

  it('★포인터가_패널_안으로_들어가는_이동이면_닫지_않는다_그래야_스크롤할_수_있다', () => {
    renderWithProviders(<ToolBar />);
    const button = screen.getByTestId(HELP_BUTTON);
    fireEvent.mouseEnter(button);
    const box = screen.getByTestId('label-toolbar-shortcut-panel-box');

    // 패널로 넘어가는 이동(relatedTarget=패널 내부)은 닫지 않는다 — pointer-events-none 이던
    // 구 구현에서는 넘칠 때 마우스로 스크롤할 방법이 없었다(WCAG 1.4.13 hoverable).
    fireEvent.mouseLeave(button, { relatedTarget: box });
    expect(screen.getByTestId(HELP_PANEL)).toBeInTheDocument();

    // 패널에서 벗어나면 닫힌다(열린 채 남지 않는다).
    fireEvent.mouseLeave(screen.getByTestId(HELP_PANEL));
    expect(screen.queryByTestId(HELP_PANEL)).toBeNull();
  });

  it('★도구바_도움말_버튼의_접근성_이름은_헤더_버튼과_구분된다', () => {
    // 같은 화면에 같은 이름의 버튼이 둘(헤더 ?, 도구바)이면 보조기술로 구별되지 않는다.
    // 두 진입점 모두 확정 사양이라 한쪽을 없애는 것은 답이 아니다 → 이름으로 구분한다.
    renderWithProviders(<ToolBar />);
    expect(screen.getByTestId(HELP_BUTTON)).toHaveAttribute('aria-label', '단축키 도움말 미리 보기');
  });

  it('포털_모드면_미제공_도구의_단축키_안내를_노출하지_않는다', () => {
    // 포털 미제공 도구(선택 객체 AI 추적·스켈레톤)는 안내하지 않는다 — 모달 표면과 동일 정책이어야
    // 한다(표면마다 정책이 갈리면 한쪽에서만 존재하지 않는 기능을 안내하게 된다).
    // ★반전(2026-09-15) — AI 분할(G)은 포털에서도 동작하므로 안내에 선다. 도구바 버튼 이름과 섞이지
    //   않게 판정 범위를 도움말 패널로 좁힌다.
    renderWithProviders(<ToolBar portalMode />);
    fireEvent.mouseEnter(screen.getByTestId(HELP_BUTTON));
    const panel = screen.getByTestId('label-toolbar-shortcut-panel');

    expect(within(panel).getByText('AI 분할')).toBeInTheDocument();
    expect(within(panel).queryByText('AI 추적')).toBeNull();
    expect(within(panel).queryByText('스켈레톤')).toBeNull();
    // 제공 도구 안내는 그대로 — 과잉 차단 회귀 가드.
    expect(within(panel).getByText('BBOX 도구')).toBeInTheDocument();
  });
});
