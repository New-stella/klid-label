import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';

import {
  LabelMasterFormModal,
  emptyForm,
  type LabelMasterForm,
} from '../LabelMasterFormModal';

/**
 * ★ 색상 hex 는 대소문자를 모두 입력받되 즉시 대문자로 정규화해 전송한다(사양 SCREEN-035).
 * 서버는 대문자 형식만 허용하고 소문자는 거부한다.
 *
 * 구 버그: 색상 피커(type="color") 경로만 toUpperCase() 를 적용했고, 텍스트 입력 경로는
 * 사용자가 입력한 값을 그대로 저장해 소문자 hex 를 입력하면 저장이 실패했다.
 */
function renderModal(onPatch: (patch: Partial<LabelMasterForm>) => void) {
  return render(
    <LabelMasterFormModal
      open
      isEditing={false}
      form={emptyForm(0)}
      errors={{}}
      submitError={null}
      submitting={false}
      onClose={vi.fn()}
      onSubmit={vi.fn()}
      onPatch={onPatch}
    />,
  );
}

describe('LabelMasterFormModal — 색상 텍스트 입력 정규화', () => {
  it('텍스트_입력으로_소문자_hex를_입력하면_대문자로_정규화되어_전달된다', () => {
    const onPatch = vi.fn();
    renderModal(onPatch);

    const colorInput = screen.getByLabelText('색상', { selector: 'input[type="text"]' });
    fireEvent.change(colorInput, { target: { value: '#a1b2c3' } });

    expect(onPatch).toHaveBeenCalledWith({ color: '#A1B2C3' });
  });
});
