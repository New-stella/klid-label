import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import { BatchStageSkipModal } from '../BatchStageSkipModal';

/**
 * 건너뛰기 사유 모달의 **표면 정합** 가드 — 확정 시안(SCREEN-009 `#dialog-skip-auto`) 기준.
 *
 * 제출·연타 방어 같은 동작은 이 모달을 여는 패널 시험이 화면째로 검증한다. 여기서는 그쪽이 보지
 * 않는 표면만 다루며, 컴포넌트를 직접 렌더해 그 패널의 변경에 흔들리지 않게 한다.
 */
function renderModal(props: Partial<Parameters<typeof BatchStageSkipModal>[0]> = {}) {
  return render(
    <BatchStageSkipModal
      open
      bundleLabel="오토라벨링"
      onClose={vi.fn()}
      onConfirm={vi.fn()}
      {...props}
    />,
  );
}

describe('BatchStageSkipModal — 확정 시안 표면 정합', () => {
  it('★무엇을_건너뛰는지_제목_문자열_밖에도_보여준다', () => {
    // 제목에만 있으면 사유를 적는 동안 대상이 시야에서 사라진다 — 확인 직전까지 남아 있어야 한다.
    renderModal({ bundleMembers: 'AI 탐지 · AI 분할 · 트랙 보간' });

    expect(screen.getByText('대상 묶음')).toBeInTheDocument();
    const chip = screen.getByText('오토라벨링');
    expect(chip.className, '중립 칩').toContain('bg-gray-100');
    expect(chip.className).toContain('text-label');
    expect(screen.getByText('AI 탐지 · AI 분할 · 트랙 보간'), '묶음 멤버 부제').toBeInTheDocument();
  });

  it('멤버_부제는_넘기지_않으면_줄_자체를_두지_않는다', () => {
    // 멤버가 하나뿐인 묶음(시계열)에 빈 줄을 남기면 무엇이 빠진 것처럼 읽힌다.
    renderModal({ bundleLabel: '시계열' });

    expect(screen.getByText('대상 묶음')).toBeInTheDocument();
    expect(screen.queryByText('AI 탐지 · AI 분할 · 트랙 보간')).not.toBeInTheDocument();
  });

  it('★필수_표식이_눈과_보조기술_양쪽에_있다', () => {
    // 별표만 두면 스크린리더 사용자는 아무것도 듣지 못하고, sr-only 만 두면 눈으로 훑는 사용자가
    // 못 본다. 컨트롤의 `aria-required` 는 라벨을 훑는 동선에 잡히지 않으므로 대신이 되지 않는다.
    renderModal();

    const textarea = screen.getByLabelText(/건너뛰기 사유/);
    const label = document.querySelector<HTMLLabelElement>(`label[for="${textarea.id}"]`);
    expect(label).not.toBeNull();
    expect(label!.querySelector('[aria-hidden="true"]')).toHaveTextContent('*');
    expect(label!.querySelector('.sr-only')).toHaveTextContent('(필수)');
  });

  it('★글자수_카운터가_입력을_따라간다', async () => {
    const user = userEvent.setup();
    renderModal();

    expect(screen.getByText('0/500')).toBeInTheDocument();
    await user.type(screen.getByLabelText(/건너뛰기 사유/), '벤더 장애');
    expect(screen.getByText('5/500')).toBeInTheDocument();
  });

  it('도움말이_입력칸에_연결된다', () => {
    // 문구가 화면에 있기만 하고 입력칸과 이어지지 않으면 보조기술 사용자에게는 없는 것과 같다.
    renderModal();

    const help = screen.getByText('배치 이력에 그대로 남습니다. 공백만으로는 저장되지 않습니다.');
    const textarea = screen.getByLabelText(/건너뛰기 사유/);
    expect(textarea.getAttribute('aria-describedby')).toContain(help.id);
  });

  it('취소는_주색_테두리가_아니라_중립_버튼이다', () => {
    // 취소와 건너뛰기가 둘 다 주색을 쓰면 무엇이 주 동작인지 색으로 갈리지 않는다.
    renderModal();

    const cancel = screen.getByRole('button', { name: '취소' });
    expect(cancel.className).toContain('border-gray-400');
    expect(cancel.className).toContain('bg-white');
    expect(cancel.className, '구 outline(주색 테두리·글자)로 되돌리지 말 것').not.toContain(
      'text-primary-600',
    );
  });
});
