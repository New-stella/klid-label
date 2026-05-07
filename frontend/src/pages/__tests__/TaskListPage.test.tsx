import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor } from '@testing-library/react';

import { apiClient } from '@/lib/api/client';
import { TaskListPage } from '@/pages/TaskListPage';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';

function setRole(role: 'REVIEWER' | 'WORKER', sub = 'u-7') {
  useAuthStore.setState({
    token: 'tok',
    claims: { sub, role, channel: 'INTERNAL', exp: 9999999999 },
  });
}

describe('TaskListPage', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  it('priority_deadline_컬럼_미사용_(UI_UX_4_5_정합)', async () => {
    setRole('WORKER');
    mock.onGet('/assignments').reply(200, {
      success: true,
      data: {
        content: [
          {
            id: 100,
            videoId: 1,
            cctvName: 'CCTV-1',
            workerId: 7,
            workerName: '홍길동',
            status: 'PENDING',
            assignedAt: '2026-05-07T10:00:00Z',
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

    renderWithProviders(<TaskListPage />, { initialEntries: ['/task'] });

    await waitFor(() => {
      expect(screen.getByText('CCTV-1')).toBeInTheDocument();
    });

    // priority/deadline 컬럼 헤더는 절대 노출되지 않음
    expect(screen.queryByText('우선순위')).not.toBeInTheDocument();
    expect(screen.queryByText('기한')).not.toBeInTheDocument();
    expect(screen.queryByText('마감일')).not.toBeInTheDocument();
  });

  it('작업_없을_때_EmptyState_노출', async () => {
    setRole('WORKER');
    mock.onGet('/assignments').reply(200, {
      success: true,
      data: { content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 },
      message: null,
      errorCode: null,
    });

    renderWithProviders(<TaskListPage />, { initialEntries: ['/task'] });

    await waitFor(() => {
      expect(screen.getByText('배정된 작업이 없습니다')).toBeInTheDocument();
    });
  });
});
