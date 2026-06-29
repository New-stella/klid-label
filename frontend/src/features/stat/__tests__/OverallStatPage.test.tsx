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
  // BE 카테고리 분포 9항목 (eventTypeCd=categoryKey, label=카테고리 한글명).
  eventDistribution: [
    { eventTypeCd: '010001', label: '침수(범람)', count: 100 },
    { eventTypeCd: '010002', label: '산사태', count: 90 },
    { eventTypeCd: '020001', label: '화재', count: 80 },
    { eventTypeCd: '020002', label: '쓰러짐', count: 70 },
    { eventTypeCd: '030001', label: '파손', count: 60 },
    { eventTypeCd: '040001', label: '교통사고', count: 50 },
    { eventTypeCd: '050001', label: '싸움', count: 40 },
    { eventTypeCd: '060001', label: '흉기소지', count: 30 },
    { eventTypeCd: '070001', label: '납치(유괴)', count: 20 },
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

  it('이벤트_분포_BE_9항목_순회_렌더', async () => {
    setRole('REVIEWER');
    renderWithProviders(<OverallStatPage />);

    const grid = await screen.findByTestId('event-distribution-grid');
    // 분포는 비동기 데이터 로드 후 렌더 — 항목 등장까지 대기
    const items = await within(grid).findAllByRole('listitem');
    expect(items).toHaveLength(9);
    expect(within(grid).getByText('침수(범람)')).toBeInTheDocument();
    expect(within(grid).getByText('납치(유괴)')).toBeInTheDocument();
  });

  it('처리_현황_5_카드_노출', async () => {
    setRole('REVIEWER');
    renderWithProviders(<OverallStatPage />);

    // mock 정합 — UI/UX §4-11 처리 현황 카드 라벨: 전체/완료/처리중/실패/대기
    await screen.findByText('처리중');
    const cards = screen.getByTestId('processing-cards');
    expect(within(cards).getByText('전체')).toBeInTheDocument();
    expect(within(cards).getByText('완료')).toBeInTheDocument();
    expect(within(cards).getByText('처리중')).toBeInTheDocument();
    expect(within(cards).getByText('실패')).toBeInTheDocument();
    expect(within(cards).getByText('대기')).toBeInTheDocument();
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
