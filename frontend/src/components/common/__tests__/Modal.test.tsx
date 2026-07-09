import { describe, expect, it, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { Modal } from '../Modal';

describe('Modal', () => {
  it('Modal_open_false_시_렌더하지_않음', () => {
    render(
      <Modal open={false} onClose={() => {}} title="제목">
        본문
      </Modal>,
    );
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('Modal_ESC_키로_닫기', async () => {
    const user = userEvent.setup();
    const onClose = vi.fn();
    render(
      <Modal open onClose={onClose} title="제목">
        본문
      </Modal>,
    );
    await user.keyboard('{Escape}');
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('Modal_백드롭_클릭_닫기_옵션', () => {
    const onClose = vi.fn();
    render(
      <Modal open onClose={onClose} title="제목">
        본문
      </Modal>,
    );
    fireEvent.click(screen.getByTestId('modal-backdrop'));
    expect(onClose).toHaveBeenCalled();
  });

  it('Modal_백드롭_클릭_disabled_시_닫지_않음', () => {
    const onClose = vi.fn();
    render(
      <Modal open onClose={onClose} title="제목" closeOnBackdrop={false}>
        본문
      </Modal>,
    );
    fireEvent.click(screen.getByTestId('modal-backdrop'));
    expect(onClose).not.toHaveBeenCalled();
  });

  it('IconButton_44x44_보장', () => {
    // given/when: 모달의 아이콘 전용 닫기 버튼
    render(
      <Modal open onClose={() => {}} title="제목">
        본문
      </Modal>,
    );
    const closeBtn = screen.getByRole('button', { name: '닫기' });
    // then: 아이콘 전용 버튼은 44x44(h-11 w-11) 터치타깃 확보
    expect(closeBtn.className).toMatch(/h-11/);
    expect(closeBtn.className).toMatch(/w-11/);
  });

  it('Modal_포커스_트랩_Tab_순환', async () => {
    const user = userEvent.setup();
    render(
      <Modal open onClose={() => {}} title="제목">
        <input data-testid="first" />
        <input data-testid="last" />
      </Modal>,
    );
    const last = screen.getByTestId('last');
    const closeBtn = screen.getByRole('button', { name: '닫기' });
    last.focus();
    await user.tab();
    // 닫기 버튼이 DOM 첫 번째 focusable이므로 last → 닫기 순환
    expect(document.activeElement).toBe(closeBtn);
  });
});
