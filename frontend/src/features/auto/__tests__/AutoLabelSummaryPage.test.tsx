import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { apiClient } from '@/lib/api/client';
import { AutoLabelSummaryPage } from '@/pages/AutoLabelSummaryPage';
import { useAuthStore } from '@/stores/useAuthStore';
import { renderWithProviders } from '@/test/renderWithProviders';

const summaryPayload = {
  videoId: 42,
  totalFrames: 900,
  totalLabels: 1500,
  averageConfidence: 0.82,
  vlmVerifiedCount: 1200,
  vlmRejectedCount: 80,
  buckets: [
    { bucket: 'high', count: 1000, ratio: 0.66 },
    { bucket: 'mid', count: 400, ratio: 0.27 },
    { bucket: 'low', count: 100, ratio: 0.07 },
  ],
  classDistribution: [
    { classId: 1, className: 'person', count: 800 },
    { classId: 2, className: 'vehicle', count: 700 },
  ],
  lowConfidenceFrames: [
    { srcSn: 7, frameNo: 5, confidence: 0.55, thumbnailUrl: '/t/5.jpg' },
    { srcSn: 8, frameNo: 6, confidence: 0.85, thumbnailUrl: '/t/6.jpg' },
    { srcSn: 9, frameNo: 7, confidence: 0.65, thumbnailUrl: '/t/7.jpg' },
  ],
};

function setWorker() {
  useAuthStore.setState({
    token: 'fake',
    claims: {
      sub: 'u-1',
      role: 'WORKER',
      channel: 'INTERNAL',
      exp: Math.floor(Date.now() / 1000) + 3600,
    },
  });
}

describe('AutoLabelSummaryPage', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    setWorker();
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  it('오토라벨_신뢰도_분포_3구간_렌더링', async () => {
    mock.onGet('/videos/42/auto-summary').reply(200, {
      success: true,
      data: summaryPayload,
      message: null,
      errorCode: null,
    });

    renderWithProviders(<AutoLabelSummaryPage />, {
      initialEntries: ['/auto/42'],
      routes: [{ path: '/auto/:videoId', element: <AutoLabelSummaryPage /> }],
    });

    await waitFor(() => {
      expect(screen.getByTestId('confidence-distribution')).toBeInTheDocument();
    });
    expect(screen.getByTestId('confidence-bucket-high')).toBeInTheDocument();
    expect(screen.getByTestId('confidence-bucket-mid')).toBeInTheDocument();
    expect(screen.getByTestId('confidence-bucket-low')).toBeInTheDocument();
  });

  it('낮은_신뢰도_프레임_필터_70_미만', async () => {
    mock.onGet('/videos/42/auto-summary').reply(200, {
      success: true,
      data: summaryPayload,
      message: null,
      errorCode: null,
    });

    const user = userEvent.setup();
    renderWithProviders(<AutoLabelSummaryPage />, {
      initialEntries: ['/auto/42'],
      routes: [{ path: '/auto/:videoId', element: <AutoLabelSummaryPage /> }],
    });

    await waitFor(() => {
      expect(screen.getByTestId('low-confidence-list')).toBeInTheDocument();
    });

    // 초기: 3개 모두 표시
    expect(screen.getByTestId('low-frame-7')).toBeInTheDocument();
    expect(screen.getByTestId('low-frame-8')).toBeInTheDocument();
    expect(screen.getByTestId('low-frame-9')).toBeInTheDocument();

    // 토글 클릭 — 70% 미만만
    await user.click(
      screen.getByRole('button', { name: /낮은 신뢰도 프레임만/ }),
    );

    await waitFor(() => {
      expect(screen.queryByTestId('low-frame-8')).not.toBeInTheDocument();
    });
    expect(screen.getByTestId('low-frame-7')).toBeInTheDocument(); // 0.55
    expect(screen.getByTestId('low-frame-9')).toBeInTheDocument(); // 0.65
  });

  it('잘못된_video_id_에러_상태_표시', () => {
    renderWithProviders(<AutoLabelSummaryPage />, {
      initialEntries: ['/auto/abc'],
      routes: [{ path: '/auto/:videoId', element: <AutoLabelSummaryPage /> }],
    });
    expect(screen.getByText('잘못된 영상 ID')).toBeInTheDocument();
  });
});
