import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { apiClient } from '@/lib/api/client';
import { renderWithProviders } from '@/test/renderWithProviders';

import { RejectModal } from '../components/RejectModal';

describe('RejectModal', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
  });

  afterEach(() => {
    mock.restore();
  });

  it('RejectModal_반려_사유_미입력시_제출_불가', async () => {
    renderWithProviders(<RejectModal reviewId={10} open onClose={() => {}} />);

    // 반려 확정 버튼: 사유 미입력 상태에서 비활성화 (zod min 1)
    const submitButton = screen.getByRole('button', { name: '반려 확정' });
    expect(submitButton).toBeDisabled();
  });

  it('반려_확정시_상태_REJECTED_+_작업_상태_IN_PROGRESS_복귀', async () => {
    let body: unknown;
    mock.onPost('/reviews/10/reject').reply((config) => {
      body = JSON.parse(config.data ?? '{}');
      return [
        200,
        {
          success: true,
          data: {
            id: 10,
            videoId: 1,
            cctvName: 'CCTV-1',
            workerId: 7,
            workerName: '홍길동',
            submittedAt: '2026-05-07T10:00:00Z',
            labelCount: 12,
            // 반려 → 작업 상태 IN_PROGRESS 복귀는 BE에서 처리.
            // 검수 자체 상태는 REJECTED.
            status: 'REJECTED',
          },
          message: null,
          errorCode: null,
        },
      ];
    });

    const onClose = vi.fn();
    const onSuccess = vi.fn();
    const user = userEvent.setup();

    renderWithProviders(
      <RejectModal reviewId={10} open onClose={onClose} onSuccess={onSuccess} />,
    );

    const textarea = screen.getByLabelText(/반려 사유/);
    await user.type(textarea, '라벨 누락');

    const submitButton = screen.getByRole('button', { name: '반려 확정' });
    await waitFor(() => {
      expect(submitButton).not.toBeDisabled();
    });
    await user.click(submitButton);

    await waitFor(() => {
      expect(body).toMatchObject({ reason: '라벨 누락' });
    });
    await waitFor(() => {
      expect(onClose).toHaveBeenCalled();
    });
    expect(onSuccess).toHaveBeenCalled();
  });

  it('반려_사유_텍스트_외_입력_폼_없음_(UI_UX_4_9_회귀_방지)', () => {
    renderWithProviders(<RejectModal reviewId={10} open onClose={() => {}} />);

    // 우선순위·기한·심각도·이슈선택 등 추가 입력은 절대 추가 금지 (UI/UX §4-9)
    expect(screen.queryByLabelText(/우선순위/)).not.toBeInTheDocument();
    expect(screen.queryByLabelText(/기한/)).not.toBeInTheDocument();
    expect(screen.queryByLabelText(/심각도/)).not.toBeInTheDocument();
    expect(screen.queryByLabelText(/카테고리/)).not.toBeInTheDocument();
  });
});
