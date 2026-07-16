// R4 — useLabelingShortcuts: '?'(shift+/) 로 치트시트 토글 콜백 호출.
// 입력 포커스(INPUT/TEXTAREA/contentEditable) 시 무시 규칙 유지.

import { afterEach, describe, expect, it, vi } from 'vitest';
import { fireEvent } from '@testing-library/react';

import { useLabelingShortcuts } from '../useLabelingShortcuts';
import { renderWithProviders } from '@/test/renderWithProviders';

function Harness({ onToggle }: { onToggle: () => void }) {
  useLabelingShortcuts({ onToggleCheatSheet: onToggle });
  return (
    <div>
      <span>harness</span>
      <input aria-label="text-field" />
    </div>
  );
}

describe('useLabelingShortcuts — 치트시트 토글(?)', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('물음표_키로_onToggleCheatSheet가_호출된다', () => {
    const onToggle = vi.fn();
    renderWithProviders(<Harness onToggle={onToggle} />);
    fireEvent.keyDown(window, { key: '?' });
    expect(onToggle).toHaveBeenCalledTimes(1);
  });

  it('입력필드_포커스중엔_물음표가_치트시트를_열지_않는다', () => {
    const onToggle = vi.fn();
    const { getByLabelText } = renderWithProviders(<Harness onToggle={onToggle} />);
    const input = getByLabelText('text-field');
    input.focus();
    // target 이 INPUT 이면 단축키 무시 (텍스트 입력 보호)
    fireEvent.keyDown(input, { key: '?' });
    expect(onToggle).not.toHaveBeenCalled();
  });
});
