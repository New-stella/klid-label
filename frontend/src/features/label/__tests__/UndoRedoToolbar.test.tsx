// UI-054 UndoRedoToolbar 계약 테스트.
//
// 사양: canUndo/canRedo/onUndo/onRedo 4개 prop 이 required 이며 스토어를 직접 구독하지 않는다.
// 컨테이너는 role=toolbar aria-label='실행 취소/다시 실행', 각 버튼 aria-label 은
// '실행 취소'/'다시 실행' 이고 단축키는 title 로 병기한다(SHORTCUT_KEYMAP 파생).

import { fireEvent, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import { renderWithProviders } from '@/test/renderWithProviders';

import { UndoRedoToolbar } from '../components/UndoRedoToolbar';
import { formatBindingKeys } from '../hooks/labelingKeymap';

function setup(overrides: Partial<Parameters<typeof UndoRedoToolbar>[0]> = {}) {
  const props = {
    canUndo: true,
    canRedo: true,
    onUndo: vi.fn(),
    onRedo: vi.fn(),
    ...overrides,
  };
  renderWithProviders(<UndoRedoToolbar {...props} />);
  return props;
}

describe('UndoRedoToolbar — props 계약(UI-054)', () => {
  it('★다시실행_버튼이_시각적으로_존재한다_단축키만으로_두지_않는다', () => {
    // 구 결함: redo 가 스토어·단축키에만 있고 화면 진입점이 0 이라 단축키를 모르면 기능이 없는 것과 같았다.
    setup();
    expect(screen.getByRole('button', { name: '실행 취소' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '다시 실행' })).toBeInTheDocument();
  });

  it('canUndo_canRedo_가_거짓이면_각각_비활성이다', () => {
    setup({ canUndo: false, canRedo: false });
    expect(screen.getByRole('button', { name: '실행 취소' })).toBeDisabled();
    expect(screen.getByRole('button', { name: '다시 실행' })).toBeDisabled();
  });

  it('canUndo_만_참이면_실행취소만_활성이다', () => {
    setup({ canUndo: true, canRedo: false });
    expect(screen.getByRole('button', { name: '실행 취소' })).not.toBeDisabled();
    expect(screen.getByRole('button', { name: '다시 실행' })).toBeDisabled();
  });

  it('클릭하면_주입된_콜백을_호출한다_스토어를_직접_건드리지_않는다', () => {
    const props = setup();
    fireEvent.click(screen.getByRole('button', { name: '실행 취소' }));
    fireEvent.click(screen.getByRole('button', { name: '다시 실행' }));
    expect(props.onUndo).toHaveBeenCalledTimes(1);
    expect(props.onRedo).toHaveBeenCalledTimes(1);
  });

  it('비활성일_때는_클릭해도_콜백이_호출되지_않는다', () => {
    const props = setup({ canUndo: false, canRedo: false });
    fireEvent.click(screen.getByRole('button', { name: '실행 취소' }));
    fireEvent.click(screen.getByRole('button', { name: '다시 실행' }));
    expect(props.onUndo).not.toHaveBeenCalled();
    expect(props.onRedo).not.toHaveBeenCalled();
  });

  it('접근성_컨테이너는_role_toolbar_이고_단축키는_title_로_병기된다', () => {
    setup();
    expect(screen.getByRole('toolbar', { name: '실행 취소/다시 실행' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '실행 취소' })).toHaveAttribute(
      'title',
      expect.stringContaining(formatBindingKeys('edit.undo')),
    );
    expect(screen.getByRole('button', { name: '다시 실행' })).toHaveAttribute(
      'title',
      expect.stringContaining(formatBindingKeys('edit.redo')),
    );
  });
});
