// Phase 3-B — 검수 제출 취소 버튼 노출/동작 회귀 가드.
// react-konva는 jsdom에서 실제 렌더링 안 됨 → 모킹.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

vi.mock('react-konva', async () => (await import('@/test/konvaMock')).createKonvaMock());

import { apiClient } from '@/lib/api/client';
import { LabelingPage } from '@/pages/label/LabelingPage';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';

function labelsPayload(videoId: number) {
  return {
    success: true,
    data: { frameNo: 0, srcSn: 100, videoId, labels: [], siblings: [] },
    message: null,
    errorCode: null,
  };
}

function reviewPayload(videoId: number, status: string) {
  return {
    success: true,
    data: {
      id: 1,
      videoId,
      cctvName: 'CCTV-1',
      workerId: 7,
      workerName: '홍길동',
      submittedAt: '2026-05-07T10:00:00Z',
      labelCount: 0,
      status,
    },
    message: null,
    errorCode: null,
  };
}

describe('LabelingPage 검수 제출 취소 버튼 (Phase 3-B)', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    useAuthStore.setState({
      token: 'tok',
      claims: { sub: 'u', role: 'WORKER', channel: 'INTERNAL', exp: 9999999999 },
    });
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  function renderForStatus(status: string) {
    mock.onGet('/frames/100/labels').reply(200, labelsPayload(18));
    mock.onGet('/reviews/18').reply(200, reviewPayload(18, status));
    renderWithProviders(<LabelingPage />, {
      initialEntries: ['/label/100'],
      routes: [
        { path: '/label/:id', element: <LabelingPage /> },
        { path: '/task', element: <div>TASK_LIST</div> },
      ],
    });
  }

  it('제출됨_REVIEW_PENDING_상태서_제출취소_버튼_노출', async () => {
    renderForStatus('REVIEW_PENDING');
    // 제출됨(검수 시작 전) 상태 → 제출 취소 버튼 노출
    const cancelBtn = await screen.findByTestId('cancel-submit-review-button');
    expect(cancelBtn).toBeInTheDocument();
    expect(cancelBtn).toHaveAttribute('aria-label', '검수 제출 취소');
  });

  it('ASSIGNED_상태서_제출취소_버튼_미노출', async () => {
    renderForStatus('ASSIGNED');
    // 제출 버튼은 렌더되지만 취소 버튼은 미노출 (아직 제출 전)
    await screen.findByTestId('submit-review-button');
    expect(screen.queryByTestId('cancel-submit-review-button')).not.toBeInTheDocument();
  });

  it('REVIEWING_상태서_제출취소_버튼_미노출', async () => {
    renderForStatus('REVIEWING');
    await screen.findByTestId('submit-review-button');
    // 검수 시작(REVIEWING) 후에는 취소 불가 → 버튼 미노출
    expect(screen.queryByTestId('cancel-submit-review-button')).not.toBeInTheDocument();
  });

  it('제출취소_클릭시_cancel_submit_API_호출', async () => {
    renderForStatus('REVIEW_PENDING');
    let cancelled = false;
    mock.onPost('/reviews/18/cancel-submit').reply(() => {
      cancelled = true;
      return [200, reviewPayload(18, 'ASSIGNED')];
    });

    const user = userEvent.setup();
    const cancelBtn = await screen.findByTestId('cancel-submit-review-button');
    await user.click(cancelBtn);

    await waitFor(() => {
      expect(cancelled).toBe(true);
    });
  });
});
