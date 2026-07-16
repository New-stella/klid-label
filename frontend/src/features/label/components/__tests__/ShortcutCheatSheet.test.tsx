// R4 — ShortcutCheatSheet: SHORTCUT_KEYMAP(단일 출처)에서 kind별로 파생한
// 단축키 치트시트 모달. 키 표기는 formatBindingKeys(대표키) 규칙과 일관.

import { afterEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';

import { ShortcutCheatSheet } from '../ShortcutCheatSheet';

describe('ShortcutCheatSheet — 단축키 치트시트 모달', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('open이_false면_아무것도_렌더하지_않는다', () => {
    render(<ShortcutCheatSheet open={false} onClose={vi.fn()} />);
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('open이_true면_다이얼로그와_kind별_그룹을_표시한다', () => {
    render(<ShortcutCheatSheet open onClose={vi.fn()} />);
    // 모달(dialog) 렌더
    expect(screen.getByRole('dialog')).toBeInTheDocument();
    // kind별 그룹 헤딩: 도구 / 프레임 이동 / 액션
    expect(screen.getByText('도구')).toBeInTheDocument();
    expect(screen.getByText('프레임 이동')).toBeInTheDocument();
    expect(screen.getByText('액션')).toBeInTheDocument();
  });

  it('SHORTCUT_KEYMAP의_대표_바인딩을_표시한다', () => {
    render(<ShortcutCheatSheet open onClose={vi.fn()} />);
    // 도구
    expect(screen.getByText('BBOX 도구')).toBeInTheDocument();
    // 프레임(nav)
    expect(screen.getByText('첫 프레임')).toBeInTheDocument();
    // 액션 — 저장 라벨 + 대표키 Ctrl+S
    expect(screen.getByText('저장')).toBeInTheDocument();
    expect(screen.getByText('Ctrl+S')).toBeInTheDocument();
    // BBOX 대표키 B
    expect(screen.getByText('B')).toBeInTheDocument();
  });

  it('같은_id의_별칭은_중복행_없이_대표키로_한_번만_표시한다', () => {
    render(<ShortcutCheatSheet open onClose={vi.fn()} />);
    // label.delete = R/Del/Backspace → 대표 라벨('객체 삭제') 1행만
    expect(screen.getAllByText('객체 삭제')).toHaveLength(1);
    // 대표키는 이름있는 특수키 Del (formatBindingKeys 규칙과 일관)
    expect(screen.getByText('Del')).toBeInTheDocument();
  });

  it('ESC로_닫힌다', () => {
    const onClose = vi.fn();
    render(<ShortcutCheatSheet open onClose={onClose} />);
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(onClose).toHaveBeenCalledTimes(1);
  });
});
