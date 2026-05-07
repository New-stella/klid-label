import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor, within } from '@testing-library/react';

import { apiClient } from '@/lib/api/client';
import { DashboardPage } from '@/pages/DashboardPage';
import { useAuthStore } from '@/stores/useAuthStore';
import { renderWithProviders } from '@/test/renderWithProviders';

const samplePayload = {
  pendingCount: 12,
  completedCount: 308,
  myTaskCount: 38,
  rejectedCount: 3,
  cumulativeImageCount: 50000,
  cumulativeVideoCount: 1500,
  eventDistribution: [
    { eventTypeCd: 'FALL', label: '낙상', count: 80 },
    { eventTypeCd: 'VIOLENCE', label: '폭력', count: 50 },
  ],
  myTask: {
    pendingCount: 1,
    inProgressCount: 2,
    reviewPendingCount: 0,
    rejectedCount: 1,
  },
  notices: [
    { id: 1, title: '시스템 점검 안내', pinned: true, createdAt: '2026-05-01T00:00:00Z' },
  ],
};

function setRole(role: 'REVIEWER' | 'WORKER') {
  useAuthStore.setState({
    token: 'fake',
    claims: {
      sub: 'u-1',
      role,
      channel: 'INTERNAL',
      exp: Math.floor(Date.now() / 1000) + 3600,
    },
  });
}

describe('DashboardPage', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    mock.onGet('/stats/summary').reply(200, {
      success: true,
      data: samplePayload,
      message: null,
      errorCode: null,
    });
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  it('대시보드_WORKER_KPI_4개_처리대기_처리완료_내작업_반려건수', async () => {
    setRole('WORKER');
    renderWithProviders(<DashboardPage />);

    await waitFor(() => {
      expect(screen.getByText('처리 대기')).toBeInTheDocument();
    });

    const grid = screen.getByTestId('dashboard-kpi-grid');
    expect(within(grid).getByText('처리 대기')).toBeInTheDocument();
    expect(within(grid).getByText('처리 완료')).toBeInTheDocument();
    expect(within(grid).getByText('내 작업')).toBeInTheDocument();
    expect(within(grid).getByText('반려 건수')).toBeInTheDocument();
  });

  it('대시보드_REVIEWER_KPI_3개_처리대기_처리완료_반려건수', async () => {
    setRole('REVIEWER');
    renderWithProviders(<DashboardPage />);

    await waitFor(() => {
      expect(screen.getByText('처리 대기')).toBeInTheDocument();
    });

    const grid = screen.getByTestId('dashboard-kpi-grid');
    expect(within(grid).getByText('처리 대기')).toBeInTheDocument();
    expect(within(grid).getByText('처리 완료')).toBeInTheDocument();
    expect(within(grid).getByText('반려 건수')).toBeInTheDocument();
    expect(within(grid).queryByText('내 작업')).not.toBeInTheDocument();
    expect(screen.queryByTestId('my-task-card')).not.toBeInTheDocument();
  });

  it('이벤트_분포_그리드_6종_고정_렌더링', async () => {
    setRole('WORKER');
    renderWithProviders(<DashboardPage />);

    await waitFor(() => {
      expect(screen.getByTestId('event-distribution-grid')).toBeInTheDocument();
    });

    const grid = screen.getByTestId('event-distribution-grid');
    // 데이터에 2종만 있어도 6종 고정 렌더
    const items = within(grid).getAllByRole('listitem');
    expect(items).toHaveLength(6);
  });

  it('이벤트_분포_6종_FALL_VIOLENCE_TRAFFIC_ACCIDENT_ABNORMAL_BEHAVIOR_FLOOD_WILDFIRE_노출', async () => {
    setRole('WORKER');
    renderWithProviders(<DashboardPage />);

    const grid = await screen.findByTestId('event-distribution-grid');
    // 6종 코드/레이블이 정확히 노출되는지 검증 (UI/UX §4-3 정합)
    expect(grid.querySelector('[data-event-type="FALL"]')).not.toBeNull();
    expect(grid.querySelector('[data-event-type="VIOLENCE"]')).not.toBeNull();
    expect(grid.querySelector('[data-event-type="TRAFFIC_ACCIDENT"]')).not.toBeNull();
    expect(grid.querySelector('[data-event-type="ABNORMAL_BEHAVIOR"]')).not.toBeNull();
    expect(grid.querySelector('[data-event-type="FLOOD"]')).not.toBeNull();
    expect(grid.querySelector('[data-event-type="WILDFIRE"]')).not.toBeNull();

    expect(within(grid).getByText('낙상')).toBeInTheDocument();
    expect(within(grid).getByText('폭력')).toBeInTheDocument();
    expect(within(grid).getByText('교통사고')).toBeInTheDocument();
    expect(within(grid).getByText('이상행동')).toBeInTheDocument();
    expect(within(grid).getByText('침수')).toBeInTheDocument();
    expect(within(grid).getByText('산불')).toBeInTheDocument();
  });

  it('WORKER_내_작업_현황_카드_노출', async () => {
    setRole('WORKER');
    renderWithProviders(<DashboardPage />);

    await waitFor(() => {
      expect(screen.getByTestId('my-task-card')).toBeInTheDocument();
    });
  });

  it('공지사항_카드_노출', async () => {
    setRole('REVIEWER');
    renderWithProviders(<DashboardPage />);

    // 데이터 로드 후 공지 제목 노출까지 대기
    await waitFor(() => {
      expect(screen.getByText('시스템 점검 안내')).toBeInTheDocument();
    });
    expect(screen.getByTestId('notice-card')).toBeInTheDocument();
  });
});
