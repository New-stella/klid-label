import { screen, within } from '@testing-library/react';
import MockAdapter from 'axios-mock-adapter';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { apiClient } from '@/lib/api/client';
import { OverallStatPage } from '@/pages/OverallStatPage';
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
  cumulativeImageCount: 50000,
  cumulativeVideoCount: 1500,
  processing: {
    pending: 1,
    inProgress: 2,
    reviewPending: 3,
    approved: 4,
    rejected: 5,
  },
  eventDistribution: [
    { eventTypeCd: 'FALL', label: '낙상', count: 100 },
  ],
  workers: [
    { userId: 1, name: '홍길동', labeled: 100, reviewed: 50, approvalRate: 95.0 },
  ],
};

describe('OverallStatPage', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    mock.onGet('/stats/overall').reply(200, {
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

  it('통계_누적_카드_ProgressBar_미노출', async () => {
    setRole('REVIEWER');
    const { container } = renderWithProviders(<OverallStatPage />);

    await screen.findByText('누적 이미지');
    const cards = screen.getByTestId('cumulative-cards');
    // ProgressBar / progressbar role 절대 미노출 (UI/UX §4-11 회귀 방지)
    expect(within(cards).queryByRole('progressbar')).not.toBeInTheDocument();
    // class 기반 회귀 방지 — 진행률 % 텍스트도 없어야 함
    expect(within(cards).queryByText(/%/)).not.toBeInTheDocument();
    // 누적 카드 렌더 자체는 정상
    expect(within(cards).getByText('누적 이미지')).toBeInTheDocument();
    expect(within(cards).getByText('누적 영상')).toBeInTheDocument();
    // KpiCard 컴포넌트는 progress prop 자체가 없으므로 구조적으로 표시 불가
    expect(container.querySelectorAll('progress')).toHaveLength(0);
  });

  it('이벤트_분포_6종_고정', async () => {
    setRole('REVIEWER');
    renderWithProviders(<OverallStatPage />);

    const grid = await screen.findByTestId('event-distribution-grid');
    expect(within(grid).getAllByRole('listitem')).toHaveLength(6);
  });

  it('처리_현황_5_카드_노출', async () => {
    setRole('REVIEWER');
    renderWithProviders(<OverallStatPage />);

    await screen.findByText('진행중');
    const cards = screen.getByTestId('processing-cards');
    expect(within(cards).getByText('대기')).toBeInTheDocument();
    expect(within(cards).getByText('진행중')).toBeInTheDocument();
    // '검수 대기'는 페이지 헤더와 카드에 모두 등장하므로 within(cards)에서 찾음
    expect(within(cards).getByText('검수 대기')).toBeInTheDocument();
    expect(within(cards).getByText('승인')).toBeInTheDocument();
    expect(within(cards).getByText('반려')).toBeInTheDocument();
  });

  it('리포트_다운로드_버튼_노출', async () => {
    setRole('REVIEWER');
    renderWithProviders(<OverallStatPage />);

    expect(screen.getByTestId('download-report-btn')).toBeInTheDocument();
  });

  it('OverallStatPage_REVIEWER만_접근', () => {
    // REVIEWER 라우터 가드 검증 — 라우트 정의 자체가 internalReviewerOnly로 잠겨 있는지 확인.
    // 라우트 가드는 별도 가드 테스트(routerGuards)에서 검증되므로 여기선 페이지 렌더 가능 여부만 점검.
    setRole('REVIEWER');
    renderWithProviders(<OverallStatPage />);
    expect(screen.getByText('전체 구축 현황')).toBeInTheDocument();
  });
});
