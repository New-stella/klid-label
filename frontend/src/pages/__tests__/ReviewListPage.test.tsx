import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { apiClient } from '@/lib/api/client';
import { ReviewListPage } from '@/pages/ReviewListPage';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';

describe('ReviewListPage', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    useAuthStore.setState({
      token: 'tok',
      claims: { sub: 'u', role: 'REVIEWER', channel: 'INTERNAL', exp: 9999999999 },
    });
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  it('검수_대기_목록_DataTable_렌더링_및_검수_시작_버튼_네비게이션', async () => {
    mock.onGet('/reviews').reply(200, {
      success: true,
      data: {
        content: [
          {
            id: 10,
            videoId: 1,
            cctvName: 'CCTV-A',
            workerId: 7,
            workerName: '홍길동',
            submittedAt: '2026-05-07T10:00:00Z',
            labelCount: 12,
            status: 'REVIEW_PENDING',
          },
        ],
        totalElements: 1,
        totalPages: 1,
        number: 0,
        size: 20,
      },
      message: null,
      errorCode: null,
    });

    const user = userEvent.setup();
    renderWithProviders(<ReviewListPage />, {
      initialEntries: ['/review'],
      routes: [
        { path: '/review', element: <ReviewListPage /> },
        { path: '/review/:id', element: <div>REVIEW_PAGE_:id</div> },
      ],
    });

    await waitFor(() => {
      expect(screen.getByText('CCTV-A')).toBeInTheDocument();
    });
    expect(screen.getByText('홍길동')).toBeInTheDocument();
    expect(screen.getByText('12')).toBeInTheDocument();

    const startBtn = screen.getByRole('button', { name: /검수 시작 CCTV-A/ });
    await user.click(startBtn);

    await waitFor(() => {
      expect(screen.getByText('REVIEW_PAGE_:id')).toBeInTheDocument();
    });
  });

  it('빈_목록일_때_EmptyState_노출', async () => {
    mock.onGet('/reviews').reply(200, {
      success: true,
      data: { content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 },
      message: null,
      errorCode: null,
    });

    renderWithProviders(<ReviewListPage />, { initialEntries: ['/review'] });

    await waitFor(() => {
      expect(screen.getByText('검수 대기 항목이 없습니다')).toBeInTheDocument();
    });
  });
});
