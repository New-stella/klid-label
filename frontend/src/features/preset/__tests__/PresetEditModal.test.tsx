import { fireEvent, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import { PresetEditModal } from '@/features/preset/components/PresetEditModal';
import type { Preset } from '@/features/preset/types';
import { renderWithProviders } from '@/test/renderWithProviders';

describe('PresetEditModal', () => {
  it('초기값_라벨_코드_렌더링', () => {
    const initial: Preset = {
      id: 1,
      name: '교통사고 표준',
      description: '교통사고용',
      labelCodes: ['PERSON', 'VEHICLE'],
      createdAt: '2026-05-01T00:00:00Z',
      updatedAt: '2026-05-10T00:00:00Z',
    };
    renderWithProviders(
      <PresetEditModal open onClose={() => undefined} onSubmit={vi.fn()} initial={initial} />,
    );

    expect(screen.getByTestId('preset-labels-count').textContent).toContain('2');
    expect(screen.getByText('PERSON')).toBeInTheDocument();
    expect(screen.getByText('VEHICLE')).toBeInTheDocument();
  });

  it('라벨_코드_미입력_시_zod_에러_노출', async () => {
    const onSubmit = vi.fn();
    renderWithProviders(
      <PresetEditModal open onClose={() => undefined} onSubmit={onSubmit} />,
    );

    fireEvent.change(screen.getByLabelText(/프리셋 이름/), {
      target: { value: '새 프리셋' },
    });
    fireEvent.click(screen.getByText('만들기'));

    await waitFor(() => {
      expect(screen.getByText(/1개 이상/)).toBeInTheDocument();
    });
    expect(onSubmit).not.toHaveBeenCalled();
  });
});
