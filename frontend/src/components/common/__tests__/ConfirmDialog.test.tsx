import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { ConfirmDialog } from '../ConfirmDialog';

describe('ConfirmDialog', () => {
  it('확인_클릭시_onConfirm_호출', async () => {
    const user = userEvent.setup();
    const onConfirm = vi.fn();
    render(
      <ConfirmDialog
        open
        title="삭제"
        onConfirm={onConfirm}
        onCancel={() => {}}
      />,
    );
    await user.click(screen.getByRole('button', { name: '확인' }));
    expect(onConfirm).toHaveBeenCalledTimes(1);
  });

  it('기본_동작_유지_ESC_입력시_onCancel_호출', async () => {
    // given: closeOnEsc 미지정 → Modal 기본값(true) 유지 (기존 호출부 회귀 방지).
    const user = userEvent.setup();
    const onCancel = vi.fn();
    render(
      <ConfirmDialog open title="삭제" onConfirm={() => {}} onCancel={onCancel} />,
    );
    await user.keyboard('{Escape}');
    expect(onCancel).toHaveBeenCalledTimes(1);
  });

  it('기본_동작_유지_백드롭_클릭시_onCancel_호출', () => {
    // given: closeOnBackdrop 미지정 → Modal 기본값(true) 유지.
    const onCancel = vi.fn();
    render(
      <ConfirmDialog open title="삭제" onConfirm={() => {}} onCancel={onCancel} />,
    );
    fireEvent.click(screen.getByTestId('modal-backdrop'));
    expect(onCancel).toHaveBeenCalledTimes(1);
  });

  it('H1_closeOnEsc_false_전달시_ESC_입력해도_onCancel_미호출', async () => {
    // given: 처리 중 강제 닫힘 방지를 위해 closeOnEsc=false 패스스루.
    const user = userEvent.setup();
    const onCancel = vi.fn();
    render(
      <ConfirmDialog
        open
        title="삭제"
        closeOnEsc={false}
        onConfirm={() => {}}
        onCancel={onCancel}
      />,
    );
    await user.keyboard('{Escape}');
    // then: Modal 이 ESC 핸들러를 등록하지 않아 onCancel 미호출 (다이얼로그 유지).
    expect(onCancel).not.toHaveBeenCalled();
  });

  it('H1_closeOnBackdrop_false_전달시_백드롭_클릭해도_onCancel_미호출', () => {
    const onCancel = vi.fn();
    render(
      <ConfirmDialog
        open
        title="삭제"
        closeOnBackdrop={false}
        onConfirm={() => {}}
        onCancel={onCancel}
      />,
    );
    fireEvent.click(screen.getByTestId('modal-backdrop'));
    expect(onCancel).not.toHaveBeenCalled();
  });
});
