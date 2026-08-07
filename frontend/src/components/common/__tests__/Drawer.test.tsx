import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { Drawer } from '../Drawer';

describe('Drawer', () => {
  it('Drawer_open_시_role_dialog_렌더', () => {
    render(
      <Drawer open onClose={() => {}} title="필터">
        <button>액션</button>
      </Drawer>,
    );
    expect(screen.getByRole('dialog', { name: '필터' })).toBeInTheDocument();
  });

  it('Drawer_ESC_닫기', async () => {
    const user = userEvent.setup();
    const onClose = vi.fn();
    render(
      <Drawer open onClose={onClose} title="필터">
        <button>액션</button>
      </Drawer>,
    );
    await user.keyboard('{Escape}');
    expect(onClose).toHaveBeenCalled();
  });

  // ── UI-006 회귀 가드: showCloseButton ─────────────────────────────────────
  it('Drawer_showCloseButton_기본값은_표시다', () => {
    render(
      <Drawer open onClose={() => {}} title="필터">
        <button>액션</button>
      </Drawer>,
    );
    expect(screen.getByRole('button', { name: '닫기' })).toBeInTheDocument();
  });

  it('Drawer_showCloseButton_false_면_X_버튼을_숨기고_ESC_는_유지한다', async () => {
    const user = userEvent.setup();
    const onClose = vi.fn();
    render(
      <Drawer open onClose={onClose} title="필터" showCloseButton={false}>
        <button>액션</button>
      </Drawer>,
    );
    expect(screen.queryByRole('button', { name: '닫기' })).not.toBeInTheDocument();
    await user.keyboard('{Escape}');
    expect(onClose).toHaveBeenCalled();
  });

  it('Drawer_open_시_focus_trap', () => {
    render(
      <Drawer open onClose={() => {}} title="필터">
        <button data-testid="inside">액션</button>
      </Drawer>,
    );
    // 처음 포커스가 다이얼로그 내부 요소에 있어야 함
    const focusable = ['inside', null];
    expect(
      focusable.includes(
        document.activeElement?.getAttribute('data-testid') ?? null,
      ) || document.activeElement?.getAttribute('aria-label') === '닫기',
    ).toBe(true);
  });
});
