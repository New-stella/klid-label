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
      eventTypeCd: null,
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

  it('이벤트_타입_select_옵션_표시', () => {
    renderWithProviders(
      <PresetEditModal open onClose={() => undefined} onSubmit={vi.fn()} />,
    );

    const select = screen.getByLabelText(/매핑 이벤트 타입/);
    expect(select).toBeInTheDocument();
    // 미선택 옵션 + EVT_FALL 등 5개 이벤트
    expect(screen.getByRole('option', { name: /선택 안 함/ })).toBeInTheDocument();
    expect(screen.getByRole('option', { name: /EVT_FALL/ })).toBeInTheDocument();
    expect(screen.getByRole('option', { name: /EVT_VIOLENCE/ })).toBeInTheDocument();
    expect(screen.getByRole('option', { name: /EVT_ACCIDENT/ })).toBeInTheDocument();
    expect(screen.getByRole('option', { name: /EVT_FIRE/ })).toBeInTheDocument();
    expect(screen.getByRole('option', { name: /EVT_TRASH/ })).toBeInTheDocument();
  });

  it('초기값_eventTypeCd_select_반영', () => {
    const initial: Preset = {
      id: 2,
      name: '낙상',
      description: null,
      labelCodes: ['PERSON'],
      eventTypeCd: 'EVT_FALL',
      createdAt: '2026-05-01T00:00:00Z',
      updatedAt: '2026-05-10T00:00:00Z',
    };
    renderWithProviders(
      <PresetEditModal open onClose={() => undefined} onSubmit={vi.fn()} initial={initial} />,
    );

    const select = screen.getByLabelText(/매핑 이벤트 타입/) as HTMLSelectElement;
    expect(select.value).toBe('EVT_FALL');
  });

  it('eventTypeCd_선택값이_onSubmit_payload에_포함', async () => {
    const onSubmit = vi.fn();
    renderWithProviders(
      <PresetEditModal open onClose={() => undefined} onSubmit={onSubmit} />,
    );

    fireEvent.change(screen.getByLabelText(/프리셋 이름/), {
      target: { value: '낙상 표준' },
    });
    // 빠른 추가에서 PERSON 클릭
    fireEvent.click(screen.getByRole('button', { name: /\+ PERSON/ }));
    fireEvent.change(screen.getByLabelText(/매핑 이벤트 타입/), {
      target: { value: 'EVT_FALL' },
    });
    fireEvent.click(screen.getByText('만들기'));

    await waitFor(() => {
      expect(onSubmit).toHaveBeenCalledTimes(1);
    });
    expect(onSubmit.mock.calls[0]![0]).toMatchObject({
      name: '낙상 표준',
      labelCodes: ['PERSON'],
      eventTypeCd: 'EVT_FALL',
    });
  });

  it('eventTypeCd_미선택시_빈_문자열_제출', async () => {
    const onSubmit = vi.fn();
    renderWithProviders(
      <PresetEditModal open onClose={() => undefined} onSubmit={onSubmit} />,
    );

    fireEvent.change(screen.getByLabelText(/프리셋 이름/), {
      target: { value: '미매핑' },
    });
    fireEvent.click(screen.getByRole('button', { name: /\+ PERSON/ }));
    fireEvent.click(screen.getByText('만들기'));

    await waitFor(() => {
      expect(onSubmit).toHaveBeenCalledTimes(1);
    });
    expect(onSubmit.mock.calls[0]![0]).toMatchObject({
      eventTypeCd: '',
    });
  });
});
