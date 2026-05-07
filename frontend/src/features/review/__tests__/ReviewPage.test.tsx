import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { apiClient } from '@/lib/api/client';
import { renderWithProviders } from '@/test/renderWithProviders';

import { ReviewPage } from '@/pages/ReviewPage';

const baseReview = {
  id: 10,
  videoId: 1,
  cctvName: 'CCTV-1',
  workerId: 7,
  workerName: '홍길동',
  submittedAt: '2026-05-07T10:00:00Z',
  labelCount: 12,
};

describe('ReviewPage', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    // 이슈는 비어있음
    mock.onGet('/reviews/10/issues').reply(200, {
      success: true,
      data: [],
      message: null,
      errorCode: null,
    });
  });

  afterEach(() => {
    mock.restore();
  });

  it('검수_시작_버튼_클릭시_상태_REVIEWING_전이', async () => {
    // 첫 GET — REVIEW_PENDING
    let getCount = 0;
    mock.onGet('/reviews/10').reply(() => {
      getCount += 1;
      return [
        200,
        {
          success: true,
          data: {
            ...baseReview,
            status: getCount === 1 ? 'REVIEW_PENDING' : 'REVIEWING',
          },
          message: null,
          errorCode: null,
        },
      ];
    });

    let started = false;
    mock.onPost('/reviews/10/start').reply(() => {
      started = true;
      return [
        200,
        {
          success: true,
          data: { ...baseReview, status: 'REVIEWING' },
          message: null,
          errorCode: null,
        },
      ];
    });

    renderWithProviders(<ReviewPage />, {
      initialEntries: ['/review/10'],
      routes: [{ path: '/review/:id', element: <ReviewPage /> }],
    });

    // 진입 시 자동 startReview 호출 (REVIEW_PENDING → REVIEWING)
    await waitFor(() => {
      expect(started).toBe(true);
    });
  });

  it('승인시_상태_COMPLETED_전이', async () => {
    mock.onGet('/reviews/10').reply(200, {
      success: true,
      data: { ...baseReview, status: 'REVIEWING' },
      message: null,
      errorCode: null,
    });

    let approveBody: unknown;
    mock.onPost('/reviews/10/approve').reply((config) => {
      approveBody = config.data ? JSON.parse(config.data) : null;
      return [
        200,
        {
          success: true,
          data: { ...baseReview, status: 'COMPLETED' },
          message: null,
          errorCode: null,
        },
      ];
    });

    const user = userEvent.setup();
    renderWithProviders(<ReviewPage />, {
      initialEntries: ['/review/10'],
      routes: [
        { path: '/review/:id', element: <ReviewPage /> },
        { path: '/review', element: <div>REVIEW_LIST</div> },
      ],
    });

    // 승인 버튼 클릭 → ConfirmDialog 노출
    const approveButtons = await screen.findAllByRole('button', { name: '승인' });
    await user.click(approveButtons[0]);

    // 확정 버튼 클릭
    const confirmButton = await screen.findByRole('button', { name: '승인 확정' });
    await user.click(confirmButton);

    await waitFor(() => {
      expect(approveBody).toBeDefined();
    });
    // navigate('/review') 확인
    await waitFor(() => {
      expect(screen.getByText('REVIEW_LIST')).toBeInTheDocument();
    });
  });

  it('검수_화면_캔버스_좌표_마커_컴포넌트_미사용', async () => {
    mock.onGet('/reviews/10').reply(200, {
      success: true,
      data: { ...baseReview, status: 'REVIEWING' },
      message: null,
      errorCode: null,
    });

    const { container } = renderWithProviders(<ReviewPage />, {
      initialEntries: ['/review/10'],
      routes: [{ path: '/review/:id', element: <ReviewPage /> }],
    });

    await waitFor(() => {
      expect(screen.getByTestId('review-canvas-readonly')).toBeInTheDocument();
    });

    // 캔버스 좌표 마커 — UI/UX §4-9 정합으로 절대 사용 금지 (회귀 방지).
    // 1) marker DOM 노드 미사용
    expect(container.querySelector('[data-marker]')).toBeNull();
    expect(container.querySelector('[data-testid*="marker"]')).toBeNull();
    expect(container.querySelector('[data-testid*="coordinate"]')).toBeNull();
    // 2) IssueSidebar는 텍스트 카드만 — 캔버스 클릭 → 좌표 추가 흐름 없음
    expect(container.querySelector('[data-testid="issue-coord-marker"]')).toBeNull();
  });
});
