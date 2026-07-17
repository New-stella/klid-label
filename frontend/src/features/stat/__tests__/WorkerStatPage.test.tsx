import { screen, waitFor } from '@testing-library/react';
import MockAdapter from 'axios-mock-adapter';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { apiClient } from '@/lib/api/client';
import { WorkerStatPage } from '@/pages/WorkerStatPage';
import { useAuthStore } from '@/stores/useAuthStore';
import { renderWithProviders } from '@/test/renderWithProviders';

function setRole(role: 'WORKER' | 'REVIEWER') {
  useAuthStore.setState({
    token: 'fake',
    claims: {
      sub: '11',
      role,
      channel: 'INTERNAL',
      exp: Math.floor(Date.now() / 1000) + 3600,
    },
  });
}

const sample = {
  workerId: '11',
  workerName: '홍길동',
  completed: 1234,
  inProgress: 5,
  rejected: 8,
  labelCount: 9876,
  autoLabelRate: 0.42,
  rejectRate: 0.05,
  dailyCompletion: [
    { date: '2026-05-01', count: 10 },
    { date: '2026-05-02', count: 20 },
  ],
  monthly: [
    { month: '2026-04', completed: 500, rejected: 3, labelCount: 1000 },
  ],
};

describe('WorkerStatPage', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    mock.onGet('/stats/worker').reply(200, {
      success: true,
      data: sample,
      message: null,
      errorCode: null,
    });
    mock.onGet('/users').reply(200, {
      success: true,
      data: { content: [], totalElements: 0, totalPages: 0, number: 0, size: 0 },
      message: null,
      errorCode: null,
    });
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  it('WorkerStatPage_KPI_4개_노출', async () => {
    setRole('WORKER');
    renderWithProviders(<WorkerStatPage />);

    await waitFor(() => {
      expect(screen.getByText('1,234')).toBeInTheDocument();
    });

    const grid = screen.getByTestId('worker-kpi-grid');
    // KpiCard 는 label 을 <p class="truncate text-sm font-medium text-gray-500"> 로 렌더한다.
    const labels = Array.from(grid.querySelectorAll('p')).map((el) => el.textContent);
    expect(labels).toContain('완료 작업');
    expect(labels).toContain('작업 중');
    expect(labels).toContain('반려');
    expect(labels).toContain('총 라벨 수');
    // KpiCard 가 4개 이상 렌더되었는지 확인 (라벨 <p> + 값 <p> 페어)
    expect(grid.querySelectorAll('p').length).toBeGreaterThanOrEqual(8);
  });

  it('월별_표_노출', async () => {
    setRole('WORKER');
    renderWithProviders(<WorkerStatPage />);

    const tbl = await screen.findByTestId('worker-monthly-table');
    await waitFor(() => {
      expect(tbl.textContent).toContain('2026-04');
    });
  });
});
