import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor, within } from '@testing-library/react';

import { apiClient } from '@/lib/api/client';
import { DashboardPage } from '@/pages/DashboardPage';
import { useAuthStore } from '@/stores/useAuthStore';
import { renderWithProviders } from '@/test/renderWithProviders';

const samplePayload = {
  totalVideos: 1234,
  totalLabeledFrames: 56000,
  reviewPendingCount: 7,
  myAssignedCount: 3,
  cumulativeImageCount: 50000,
  cumulativeVideoCount: 1500,
  eventDistribution: [
    { eventTypeCd: 'FIRE', label: '화재', count: 100 },
    { eventTypeCd: 'FALL', label: '쓰러짐', count: 80 },
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

  it('대시보드_WORKER_KPI_4개_노출', async () => {
    setRole('WORKER');
    renderWithProviders(<DashboardPage />);

    await waitFor(() => {
      expect(screen.getByText('전체 영상')).toBeInTheDocument();
    });

    const grid = screen.getByTestId('dashboard-kpi-grid');
    expect(within(grid).getByText('전체 영상')).toBeInTheDocument();
    expect(within(grid).getByText('누적 라벨 프레임')).toBeInTheDocument();
    expect(within(grid).getByText('검수 대기')).toBeInTheDocument();
    expect(within(grid).getByText('내 배정')).toBeInTheDocument();
  });

  it('대시보드_REVIEWER_KPI_3개_노출_내_작업_제외', async () => {
    setRole('REVIEWER');
    renderWithProviders(<DashboardPage />);

    await waitFor(() => {
      expect(screen.getByText('전체 영상')).toBeInTheDocument();
    });

    const grid = screen.getByTestId('dashboard-kpi-grid');
    expect(within(grid).getByText('전체 영상')).toBeInTheDocument();
    expect(within(grid).getByText('누적 라벨 프레임')).toBeInTheDocument();
    expect(within(grid).getByText('검수 대기')).toBeInTheDocument();
    expect(within(grid).queryByText('내 배정')).not.toBeInTheDocument();
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
