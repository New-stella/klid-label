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
    { eventTypeCd: 'FALL', label: '쓰러짐', count: 80 },
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
    // mock 정합 — 최근 완료 영상 빈 응답
    mock.onGet('/videos').reply(200, {
      success: true,
      data: { content: [], totalElements: 0, totalPages: 0, number: 0, size: 5 },
      message: null,
      errorCode: null,
    });
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  it('대시보드_KPI_3개_처리대기_처리완료_반려건수_노출', async () => {
    setRole('WORKER');
    renderWithProviders(<DashboardPage />);

    await waitFor(() => {
      expect(screen.getByText('처리 대기')).toBeInTheDocument();
    });

    const grid = screen.getByTestId('dashboard-kpi-grid');
    expect(within(grid).getByText('처리 대기')).toBeInTheDocument();
    expect(within(grid).getByText('처리 완료')).toBeInTheDocument();
    expect(within(grid).getByText('반려 건수')).toBeInTheDocument();
  });

  it('이미지_영상_데이터_카드_이벤트_6종_노출', async () => {
    setRole('REVIEWER');
    renderWithProviders(<DashboardPage />);

    await waitFor(() => {
      expect(screen.getByText('이미지 데이터 개수')).toBeInTheDocument();
    });

    expect(screen.getByText('영상 데이터 개수')).toBeInTheDocument();

    // 데이터 로드 후 6종 이벤트 라벨 노출 (이미지+영상 카드 양쪽 모두 있어 getAllByText 사용)
    const labels = ['쓰러짐', '폭력', '교통사고', '이상행동(유괴)', '침수', '산불'];
    for (const l of labels) {
      await waitFor(() => {
        expect(screen.getAllByText(l).length).toBeGreaterThan(0);
      });
    }
  });

  it('최근_완료_영상_섹션_제목_노출', async () => {
    setRole('REVIEWER');
    renderWithProviders(<DashboardPage />);

    await waitFor(() => {
      expect(screen.getByText('최근 완료 영상')).toBeInTheDocument();
    });
  });

  it('대시보드_제목_렌더', async () => {
    setRole('REVIEWER');
    renderWithProviders(<DashboardPage />);

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: '대시보드' })).toBeInTheDocument();
    });
  });
});
