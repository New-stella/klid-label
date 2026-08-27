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

    // ★ 취소는 시안 `.btn-secondary`(중립 테두리 + 검정 글자)다. 구 `outline` 은 글자·테두리가
    //   모두 파랑(primary)이라 확정 버튼과 나란히 놓이면 «두 번째 주 버튼»처럼 읽혔다.
    //   건너뛰기 모달의 취소가 같은 이유로 이미 `secondary` 인데 이 공용 확인창만 빠져 있었다.
    it('★취소는_중립_톤이다_확정_버튼과_위계가_갈린다', () => {
      render(
        <ConfirmDialog open title="삭제" onConfirm={() => {}} onCancel={() => {}} />,
      );
      const cancel = screen.getByRole('button', { name: '취소' });
      // secondary = 흰 배경 + 중립 400 테두리 + 중립 700 글자.
      expect(cancel.className).toContain('border-gray-400');
      expect(cancel.className).toContain('text-gray-700');
      // outline 계열(파랑 글자·파랑 테두리)로 되돌리지 말 것.
      expect(cancel.className).not.toContain('text-primary-600');
      expect(cancel.className).not.toContain('border-primary-600');
    });
  });

  // ── 본문 슬롯(설명 위 조각) ─────────────────────────────────────────────────
  //
  // 시안 확인 창의 첫 줄은 «무엇에 대한 확인인가»를 알리는 대상 칩 행(`.dlg-target`)이고 설명은
  // 그 아래다. `Modal` 본문은 `description` → `children` 순서가 고정이라 그냥 넘기면 칩 행이
  // 설명 밑으로 내려간다 — 그래서 슬롯이 있을 때만 설명을 이 컴포넌트가 직접 그린다.
  describe('본문 슬롯', () => {
    it('★슬롯을_넘기면_설명보다_위에_그려진다', () => {
      render(
        <ConfirmDialog
          open
          title="재수행"
          description="이 묶음만 수행합니다."
          onConfirm={() => {}}
          onCancel={() => {}}
        >
          <div data-testid="lead">대상 묶음</div>
        </ConfirmDialog>,
      );

      const lead = screen.getByTestId('lead');
      const desc = screen.getByText('이 묶음만 수행합니다.');
      expect(lead).toBeInTheDocument();
      // DOCUMENT_POSITION_FOLLOWING = 설명이 슬롯 **뒤**에 온다.
      expect(lead.compareDocumentPosition(desc) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    });

    it('★슬롯과_경고를_함께_넘기면_칩_설명_경고_순서다', () => {
      render(
        <ConfirmDialog
          open
          title="재수행"
          description="이 작업을 통째로 다시 수행합니다."
          warning="되돌릴 수 없습니다."
          variant="danger"
          onConfirm={() => {}}
          onCancel={() => {}}
        >
          <div data-testid="lead">대상 묶음</div>
        </ConfirmDialog>,
      );

      const lead = screen.getByTestId('lead');
      const desc = screen.getByText('이 작업을 통째로 다시 수행합니다.');
      const warn = screen.getByTestId('confirm-dialog-warning');
      expect(lead.compareDocumentPosition(desc) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
      expect(desc.compareDocumentPosition(warn) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    });

    // ★ 슬롯을 넘기지 않은 **기존 호출부의 마크업은 바뀌지 않는다** — 설명은 종전대로 `Modal` 이
    //   그린다. 이 분기가 무너지면 14개 호출부의 본문 구조가 한꺼번에 달라진다.
    it('★슬롯이_없으면_설명은_종전대로_모달이_그린다', () => {
      render(
        <ConfirmDialog
          open
          title="삭제"
          description="이 항목을 삭제합니다."
          onConfirm={() => {}}
          onCancel={() => {}}
        />,
      );

      const desc = screen.getByText('이 항목을 삭제합니다.');
      // Modal 이 그리는 설명은 본문 스크롤 영역 **밖**의 형제다(children 컨테이너 안이 아니다).
      const dialog = screen.getByRole('dialog');
      expect(desc.parentElement).toBe(dialog);
      expect(desc.tagName).toBe('P');
    });
  });
});
