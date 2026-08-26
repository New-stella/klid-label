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

  // ── 확정 시안(SCREEN-009 `.lightbox-box.is-sm` · `.dlg-warn`) 정합 ──────────────
  describe('확정 시안 표면 정합', () => {
    // K9 — 시안 폭은 `min(520px, …)`. Modal 토큰은 sm=384 · md=512 · lg=768 뿐이라 520 을 넘지
    //   않으면서 가장 가까운 md(512)를 쓴다.
    // ⚠ `Modal` 의 `sm` 토큰 자체를 넓히면 `size="sm"` 을 쓰는 다른 모달까지 끌려간다 —
    //   폭을 정하는 것은 이 확인창이지 토큰이 아니다.
    it('K9_확인창_폭은_시안_상한에_맞춘_토큰이다', () => {
      render(<ConfirmDialog open title="삭제" onConfirm={() => {}} onCancel={() => {}} />);
      const cls = screen.getByRole('dialog').className;
      expect(cls, '시안 520px 에 가장 가까운 토큰 max-w-lg(512)').toContain('max-w-lg');
      expect(cls, '구 max-w-sm(384)으로 되돌리지 말 것').not.toContain('max-w-sm');
    });

    // K8 — 되돌릴 수 없는 결과는 평문이 아니라 경고 박스(시안 `.dlg-warn`)로 알린다.
    it('K8_경고를_넘기면_평문이_아니라_경고_박스로_그린다', () => {
      render(
        <ConfirmDialog
          open
          title="재수행"
          description="이 작업을 통째로 다시 수행합니다."
          warning="되돌릴 수 없습니다."
          variant="danger"
          onConfirm={() => {}}
          onCancel={() => {}}
        />,
      );
      const box = screen.getByTestId('confirm-dialog-warning');
      expect(box).toHaveTextContent('되돌릴 수 없습니다.');
      // 시안 `.dlg-warn { background: --e-0; border: 1px solid --e-2; }`
      expect(box.className, '시안 배경 --e-0').toContain('bg-danger-50');
      expect(box.className, '시안 테두리 --e-2').toContain('border-danger-200');
      // 아이콘이 함께 선다(시안 `.dlg-warn .icon`) — 색만으로 경고를 전달하지 않는다.
      expect(box.querySelector('svg')).not.toBeNull();
      // 설명은 그대로 남는다 — 「무엇을 하는가」와 「무엇을 잃는가」는 다른 층이다.
      expect(screen.getByText('이 작업을 통째로 다시 수행합니다.')).toBeInTheDocument();
    });

    // ★ opt-in 이다 — 기존 호출부(경고를 넘기지 않는 danger 확인창)의 모양은 바뀌지 않는다.
    it('K8_경고를_넘기지_않은_danger_확인창은_박스를_얻지_않는다', () => {
      render(
        <ConfirmDialog
          open
          title="삭제"
          description="이 항목을 삭제합니다."
          variant="danger"
          onConfirm={() => {}}
          onCancel={() => {}}
        />,
      );
      expect(screen.queryByTestId('confirm-dialog-warning')).not.toBeInTheDocument();
    });
  });
});
