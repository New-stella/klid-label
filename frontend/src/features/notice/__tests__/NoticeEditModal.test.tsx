import { fireEvent, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import { NoticeEditModal } from '@/features/notice/components/NoticeEditModal';
import { renderWithProviders } from '@/test/renderWithProviders';

describe('NoticeEditModal', () => {
  it('작성_폼_제목_미입력시_검증오류', async () => {
    const onSubmit = vi.fn();
    renderWithProviders(
      <NoticeEditModal open onClose={() => undefined} onSubmit={onSubmit} />,
    );

    fireEvent.change(screen.getByLabelText(/내용/), {
      target: { value: '본문 내용' },
    });
    fireEvent.click(screen.getByText('작성'));

    await waitFor(() => {
      expect(screen.getByText(/제목은 필수/)).toBeInTheDocument();
    });
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('정상_입력시_onSubmit_payload_전달', async () => {
    const onSubmit = vi.fn();
    renderWithProviders(
      <NoticeEditModal open onClose={() => undefined} onSubmit={onSubmit} />,
    );

    fireEvent.change(screen.getByLabelText(/제목/), {
      target: { value: '점검 안내' },
    });
    fireEvent.change(screen.getByLabelText(/내용/), {
      target: { value: '02:00 점검' },
    });
    fireEvent.click(screen.getByText('작성'));

    await waitFor(() => {
      expect(onSubmit).toHaveBeenCalledTimes(1);
    });
    expect(onSubmit.mock.calls[0]![0]).toMatchObject({
      title: '점검 안내',
      content: '02:00 점검',
      pinned: false,
    });
  });
});
