import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { apiClient } from '@/lib/api/client';
import { renderWithProviders } from '@/test/renderWithProviders';
import type { Video } from '@/features/video/types';

import { AssignModal } from '../components/AssignModal';

const baseVideo: Video = {
  id: 1,
  cctvName: 'CCTV-1',
  vmsClipId: 'VMS-1',
  eventName: '낙상',
  eventTypeCd: 'FALL',
  localGov: '강남구',
  frameCount: 900,
  status: 'COMPLETED',
  capturedAt: '2026-05-01T12:00:00Z',
};

function mockWorkers(mock: MockAdapter) {
  mock.onGet('/users/workers').reply(200, {
    success: true,
    data: [
      { id: 7, name: '홍길동', active: true },
      { id: 8, name: '김작업', active: true },
    ],
    message: null,
    errorCode: null,
  });
}

describe('AssignModal', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
  });

  afterEach(() => {
    mock.restore();
  });

  it('우선순위_기한_메모_입력_없음_(UI_UX_4_5_회귀_방지)', async () => {
    mockWorkers(mock);

    renderWithProviders(<AssignModal video={baseVideo} onClose={() => {}} />);

    await waitFor(() => {
      expect(screen.getByText('홍길동')).toBeInTheDocument();
    });

    // 우선순위·기한·메모 입력은 UI/UX §4-5 정합으로 절대 없어야 함
    expect(screen.queryByLabelText(/우선순위/)).not.toBeInTheDocument();
    expect(screen.queryByLabelText(/기한/)).not.toBeInTheDocument();
    expect(screen.queryByLabelText(/메모/)).not.toBeInTheDocument();
  });

  it('작업자_선택_후_POST_assignments_호출', async () => {
    mockWorkers(mock);
    let postBody: unknown;
    mock.onPost('/assignments').reply((config) => {
      postBody = JSON.parse(config.data ?? '{}');
      return [
        201,
        {
          success: true,
          data: { id: 200, videoId: 1, workerId: 7, status: 'PENDING', assignedAt: '2026-05-07T10:00:00Z' },
          message: null,
          errorCode: null,
        },
      ];
    });

    const onClose = vi.fn();
    const onSuccess = vi.fn();
    const user = userEvent.setup();

    renderWithProviders(
      <AssignModal video={baseVideo} onClose={onClose} onSuccess={onSuccess} />,
    );

    await waitFor(() => {
      expect(screen.getByText('홍길동')).toBeInTheDocument();
    });

    // 라디오 선택
    const radio = screen.getByRole('radio', { name: /홍길동/ });
    await user.click(radio);

    // 배정 제출
    const submit = screen.getByRole('button', { name: '배정' });
    await user.click(submit);

    await waitFor(() => {
      expect(postBody).toMatchObject({ videoIds: [1], workerId: 7 });
    });
    await waitFor(() => {
      expect(onClose).toHaveBeenCalled();
    });
    expect(onSuccess).toHaveBeenCalled();
  });

  it('작업자_미선택_상태에서는_배정_버튼_disabled', async () => {
    mockWorkers(mock);

    renderWithProviders(<AssignModal video={baseVideo} onClose={() => {}} />);

    await waitFor(() => {
      expect(screen.getByText('홍길동')).toBeInTheDocument();
    });

    const submit = screen.getByRole('button', { name: '배정' });
    expect(submit).toBeDisabled();
  });
});
