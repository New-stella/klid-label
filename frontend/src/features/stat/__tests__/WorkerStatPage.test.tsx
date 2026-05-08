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
  totalLabeled: 1234,
  totalReviewed: 56,
  approvalRate: 92.5,
  averageElapsedSec: 180,
  dailyCompletion: [
    { date: '2026-05-01', count: 10 },
    { date: '2026-05-02', count: 20 },
  ],
  eventDistribution: [{ eventTypeCd: 'FALL', label: '낙상', count: 30 }],
  monthly: [{ month: '2026-04', labeled: 500, reviewed: 30, approvalRate: 90.0 }],
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
    // KpiCard label은 text-sub 클래스를 가진 span (mock tone에서는 text-gray-500)
    const labels = Array.from(grid.querySelectorAll('span.text-sub')).map(
      (el) => el.textContent,
    );
    expect(labels).toContain('누적 라벨');
    expect(labels).toContain('누적 검수');
    expect(labels).toContain('승인률');
    expect(labels).toContain('평균 소요시간');
    expect(grid.querySelectorAll('strong').length).toBeGreaterThanOrEqual(4);
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
