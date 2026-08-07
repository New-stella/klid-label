// B5#13 — 단축키 도움말의 표면이 **좌측 도구바 맨 아래 버튼 hover** 다(SCREEN-005).
//
// 검증 축:
//  ① 도구바에 도움말 트리거가 실재한다(사양 표면이 도달 가능하다)
//  ② hover 로 열린다(사양) — 그리고 **focus 로도 열린다**(hover 전용은 키보드 사용자에게
//     도달 불가라 접근성 회귀다, WCAG 2.1.1). 이 두 축은 짝이며 한쪽만 두면 안 된다.
//  ③ 표 내용은 SHORTCUT_KEYMAP 단일 출처에서 파생된다(도구바가 표기를 복제하지 않는다)
//  ④ 포털 모드에서는 미제공 도구 안내를 노출하지 않는다(모달 표면과 동일 정책)

import { fireEvent, screen } from '@testing-library/react';
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

  it('포털_모드면_미제공_도구의_단축키_안내를_노출하지_않는다', () => {
    // ADR-013 — 포털은 SAM2 분할/추적·키포인트 미제공. 모달 표면과 동일 정책이어야 한다
    // (표면마다 정책이 갈리면 한쪽에서만 존재하지 않는 기능을 안내하게 된다).
    renderWithProviders(<ToolBar portalMode />);
    fireEvent.mouseEnter(screen.getByTestId(HELP_BUTTON));

    expect(screen.queryByText('AI 분할')).toBeNull();
    expect(screen.queryByText('AI 추적')).toBeNull();
    // 제공 도구 안내는 그대로 — 과잉 차단 회귀 가드.
    expect(screen.getByText('BBOX 도구')).toBeInTheDocument();
  });
});
