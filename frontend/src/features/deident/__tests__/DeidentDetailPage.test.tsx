import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { apiClient } from '@/lib/api/client';
import { DeidentDetailPage } from '@/pages/DeidentDetailPage';
import { useAuthStore } from '@/stores/useAuthStore';
import { renderWithProviders } from '@/test/renderWithProviders';

const detailPayload = {
  videoId: 42,
  cctvName: '강남대로 CCTV',
  vmsClipId: 'VMS-42',
  prvcType: 'PRVC',
  prvcYn: 'Y',
  status: 'COMPLETED',
  totalFrames: 900,
  processedFrames: 899,
  failedFrames: 1,
  framePairs: [
    { srcSn: 1, frameNo: 1, originalUrl: '/o/1.jpg', processedUrl: '/d/1.jpg' },
    { srcSn: 2, frameNo: 2, originalUrl: '/o/2.jpg', processedUrl: '/d/2.jpg' },
    { srcSn: 3, frameNo: 3, originalUrl: '/o/3.jpg', processedUrl: undefined },
  ],
  history: [
    {
      attemptNo: 1,
      attemptedAt: '2026-05-01T10:00:00Z',
      status: 'FAILED',
      message: '비식별 API 타임아웃',
      durationMs: 60000,
    },
    { attemptNo: 2, attemptedAt: '2026-05-01T10:05:00Z', status: 'COMPLETED', durationMs: 35000 },
  ],
};

function setReviewer() {
  useAuthStore.setState({
    token: 'fake',
    claims: {
      sub: 'u-1',
      role: 'REVIEWER',
      channel: 'INTERNAL',
      exp: Math.floor(Date.now() / 1000) + 3600,
    },
  });
}

describe('DeidentDetailPage', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    setReviewer();
    mock.onGet('/deident/42').reply(200, {
      success: true,
      data: detailPayload,
      message: null,
      errorCode: null,
    });
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  it('상세_페이지_프레임_그리드_및_처리_이력_렌더링', async () => {
    renderWithProviders(<DeidentDetailPage />, {
      initialEntries: ['/deident/42'],
      routes: [{ path: '/deident/:videoId', element: <DeidentDetailPage /> }],
    });

    await waitFor(() => {
      expect(screen.getByTestId('frame-grid-12')).toBeInTheDocument();
    });
    expect(screen.getByTestId('process-history-list')).toBeInTheDocument();
    expect(screen.getByTestId('process-history-1')).toBeInTheDocument();
    expect(screen.getByTestId('process-history-2')).toBeInTheDocument();
  });

  it('12_프레임_그리드_라디오_단일_선택_큰_비교_뷰_갱신', async () => {
    const user = userEvent.setup();
    renderWithProviders(<DeidentDetailPage />, {
      initialEntries: ['/deident/42'],
      routes: [{ path: '/deident/:videoId', element: <DeidentDetailPage /> }],
    });

    await waitFor(() => {
      expect(screen.getByTestId('frame-grid-12')).toBeInTheDocument();
    });

    // 초기: 첫 프레임 자동 선택 → 좌우 비교 뷰에 src=1 이미지
    const compare = await screen.findByTestId('side-by-side-compare');
    expect(within(compare).getByTestId('side-by-side-left')).toHaveAttribute(
      'src',
      '/o/1.jpg',
    );

    // 두번째 프레임 선택
    await user.click(screen.getByTestId('frame-pair-2'));

    await waitFor(() => {
      expect(within(compare).getByTestId('side-by-side-left')).toHaveAttribute(
        'src',
        '/o/2.jpg',
      );
    });
  });

  it('비식별_실패_프레임_선택시_큰_비교_뷰에_비식별_이미지_없음_표시', async () => {
    const user = userEvent.setup();
    renderWithProviders(<DeidentDetailPage />, {
      initialEntries: ['/deident/42'],
      routes: [{ path: '/deident/:videoId', element: <DeidentDetailPage /> }],
    });

    await waitFor(() => {
      expect(screen.getByTestId('frame-grid-12')).toBeInTheDocument();
    });

    // srcSn=3 (processedUrl 없음) 선택
    await user.click(screen.getByTestId('frame-pair-3'));

    await waitFor(() => {
      expect(screen.getByTestId('side-by-side-right-missing')).toHaveTextContent(
        '비식별 이미지 없음',
      );
    });
  });
});
