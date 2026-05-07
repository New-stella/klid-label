import { fireEvent, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import { PresetEditModal } from '@/features/preset/components/PresetEditModal';
import type { Preset } from '@/features/preset/types';
import { renderWithProviders } from '@/test/renderWithProviders';

describe('PresetEditModal', () => {
  it('라벨_항목_최대_6종_제한_추가_버튼_비활성화', () => {
    const initial: Preset = {
      id: 1,
      name: '6종 가득',
      eventTypeCd: 'FIRE',
      items: Array.from({ length: 6 }, (_, i) => ({
        name: `항목${i + 1}`,
        shape: 'BBOX',
        color: '#ef4444',
      })),
    };
    renderWithProviders(
      <PresetEditModal open onClose={() => undefined} onSubmit={vi.fn()} initial={initial} />,
    );

    const addBtn = screen.getByTestId('preset-add-item-btn');
    expect(addBtn).toBeDisabled();
    expect(screen.getByTestId('preset-items-count').textContent).toContain('6 / 6');
  });

  it('이름_특수문자_시_zod_에러_노출', async () => {
    const onSubmit = vi.fn();
    renderWithProviders(<PresetEditModal open onClose={() => undefined} onSubmit={onSubmit} />);

    const nameInput = screen.getByLabelText('프리셋명');
    fireEvent.change(nameInput, { target: { value: '<script>' } });
    fireEvent.click(screen.getByText('저장'));

    await waitFor(() => {
      expect(screen.getByText(/특수문자 제한/)).toBeInTheDocument();
    });
    expect(onSubmit).not.toHaveBeenCalled();
  });
});
