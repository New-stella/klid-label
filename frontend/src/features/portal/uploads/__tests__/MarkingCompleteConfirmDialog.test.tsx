/**
 * 마킹 완료 확인 창 회귀 가드. [@design SCREEN-045]
 *
 * 이 파일이 고정하는 계약:
 *  1. **기본 초점이 취소**다 — 되돌릴 수 없는 쪽이 기본 선택이면 무심코 누른 한 번이 확정된다.
 *  2. 확정되는 내용(방식·간격·환산 시간·뽑힐 장수)을 그 자리에서 알린다.
 *  3. **절단 사실이 확인 단계에 드러난다** — 요청한 수와 실제 장수를 나란히 적고, 무엇이 남는지가
 *     방식마다 다르게 안내된다.
 *  4. 되돌릴 수 없다는 사실과 회복 경로(지우고 다시 올리기 · 추출 중 삭제 불가)를 함께 알린다.
 *  5. 저장이 나가는 동안 두 버튼을 함께 잠근다.
 */
import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { MarkingCompleteConfirmDialog } from '../components/MarkingCompleteConfirmDialog';
import { capApplied } from '../markingPlan';
import { PortalMarkingMode } from '../markingTypes';

function renderDialog(overrides: Partial<Parameters<typeof MarkingCompleteConfirmDialog>[0]> = {}) {
  const onConfirm = vi.fn();
  const onCancel = vi.fn();
  render(
    <MarkingCompleteConfirmDialog
      open
      mode={PortalMarkingMode.AUTO}
      intervalFrames={300}
      intervalSec={10}
      cap={capApplied(6, null)}
      saving={false}
      onConfirm={onConfirm}
      onCancel={onCancel}
      {...overrides}
    />,
  );
  return { onConfirm, onCancel };
}

describe('마킹 완료 확인 창', () => {
  it('★기본_초점이_취소에_있다', () => {
    renderDialog();

    // 되돌릴 수 없는 쪽(확정)이 아니라 물러나는 쪽이 첫 초점이어야 한다.
    expect(document.activeElement).toBe(screen.getByRole('button', { name: '취소' }));
    expect(document.activeElement).not.toBe(
      screen.getByRole('button', { name: '완료하고 추출 시작' }),
    );
  });

  it('본문에_초점_대상을_두지_않는다 — 그 자리로 초점이 옮겨 가면 취소 규약이 깨진다', () => {
    renderDialog();

    const dialog = screen.getByRole('dialog');
    const focusables = Array.from(
      dialog.querySelectorAll('a[href], input, select, textarea, button, [tabindex]:not([tabindex="-1"])'),
    );
    // 초점 가능한 것은 두 버튼뿐이고, 그중 앞선 것이 취소다(닫기 X 를 두지 않는 이유이기도 하다).
    expect(focusables).toHaveLength(2);
    expect(focusables[0]).toHaveTextContent('취소');
  });

  it('자동이면_간격과_환산_시간과_뽑힐_장수를_알린다', () => {
    renderDialog({ cap: capApplied(6, null) });

    expect(screen.getByTestId('marking-confirm-mode')).toHaveTextContent('자동');
    expect(screen.getByTestId('marking-confirm-interval')).toHaveTextContent('300 프레임');
    expect(screen.getByTestId('marking-confirm-interval')).toHaveTextContent('약 10.0초');
    expect(screen.getByTestId('marking-confirm-frame-count')).toHaveTextContent('6장');
  });

  it('초당_프레임_수를_모르면_환산_시간을_지어내지_않는다', () => {
    renderDialog({ intervalSec: null });

    expect(screen.getByTestId('marking-confirm-interval')).toHaveTextContent('300 프레임');
    expect(screen.getByTestId('marking-confirm-interval')).not.toHaveTextContent('초');
  });

  it('★절단이면_요청한_수와_실제_장수를_나란히_알린다 (자동)', () => {
    renderDialog({ cap: capApplied(2500, 2000) });

    const notice = screen.getByTestId('marking-confirm-truncated');
    expect(notice).toHaveTextContent('요청한 2500장 가운데 2000장만 뽑힙니다.');
    // 자동은 전 구간을 고르게 다시 뽑는다 — 앞에서 자르는 수동과 안내가 갈린다.
    expect(notice).toHaveTextContent('자동은 전 구간을 고르게 다시 뽑아');
    expect(screen.getByTestId('marking-confirm-frame-count')).toHaveTextContent('요청 2500장 중');
  });

  it('★절단이면_수동은_앞에서부터_남는다고_알린다', () => {
    renderDialog({ mode: PortalMarkingMode.MANUAL, cap: capApplied(2500, 2000) });

    expect(screen.getByTestId('marking-confirm-truncated')).toHaveTextContent(
      '수동은 앞에서부터 상한까지 남고 뒤가 빠집니다.',
    );
  });

  it('★상한을_모르면_절단을_예고하지_않는다', () => {
    renderDialog({ cap: capApplied(9999, null) });

    // 모르는 것을 아는 척하지 않는다 — 그 사실은 저장 응답이 사후에 알린다.
    expect(screen.queryByTestId('marking-confirm-truncated')).toBeNull();
    expect(screen.getByTestId('marking-confirm-frame-count')).not.toHaveTextContent('중');
  });

  it('되돌릴_수_없다는_사실과_회복_경로와_기다림을_한자리에서_알린다', () => {
    renderDialog();

    const warn = screen.getByTestId('marking-confirm-irreversible');
    expect(warn).toHaveTextContent('완료하면 되돌릴 수 없습니다.');
    expect(warn).toHaveTextContent('지우고 다시 올려야');
    expect(warn).toHaveTextContent('추출이 진행 중인 동안에는 지울 수 없습니다.');
  });

  it('확인을_누르면_확정_취소를_누르면_아무것도_하지_않는다', async () => {
    const user = userEvent.setup();
    const { onConfirm, onCancel } = renderDialog();

    await user.click(screen.getByRole('button', { name: '완료하고 추출 시작' }));
    expect(onConfirm).toHaveBeenCalledTimes(1);
    expect(onCancel).not.toHaveBeenCalled();

    await user.click(screen.getByRole('button', { name: '취소' }));
    expect(onCancel).toHaveBeenCalledTimes(1);
  });

  it('저장이_나가는_동안_두_버튼을_함께_잠근다', () => {
    renderDialog({ saving: true });

    expect(screen.getByRole('button', { name: '취소' })).toBeDisabled();
    expect(screen.getByRole('button', { name: /완료하고 추출 시작/ })).toBeDisabled();
  });
});
