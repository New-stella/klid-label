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

  it('I3_최근_완료_영상_API_오류시_빈상태가_아니라_오류_메시지와_재시도_노출', async () => {
    // given: 최근 완료 영상 API 가 500 으로 실패 (이전엔 빈 상태로 조용히 표시됨)
    mock.onGet('/videos').reply(500, {
      success: false,
      data: null,
      message: '서버 오류',
      errorCode: 'INTERNAL_ERROR',
    });
    setRole('REVIEWER');

    // when
    renderWithProviders(<DashboardPage />);

    // then: "데이터 없음" 이 아니라 로드 실패 메시지 + 재시도 버튼 노출
    await waitFor(() => {
      expect(screen.getByText('목록을 불러오지 못했습니다')).toBeInTheDocument();
    });
    expect(screen.getByRole('button', { name: '재시도' })).toBeInTheDocument();
    expect(screen.queryByText('완료된 영상이 없습니다.')).not.toBeInTheDocument();
  });
});
