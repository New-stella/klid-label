import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor } from '@testing-library/react';

import { apiClient } from '@/lib/api/client';
import { VideoStatusPage } from '@/pages/VideoStatusPage';
import { useAuthStore } from '@/stores/useAuthStore';
import { renderWithProviders } from '@/test/renderWithProviders';

const samplePayload = {
  totalProcessing: 1,
  totalCompleted: 5,
  totalFailed: 0,
  videos: [
    {
      videoId: 10,
      cctvName: '테헤란로 CCTV',
      vmsClipId: 'VMS-10',
      currentStage: 'YOLO',
      startedAt: '2026-05-07T10:00:00Z',
      stages: [
        { stage: 'FRAME_EXTRACT', label: '프레임 추출', status: 'COMPLETED', progressPercent: 100 },
        { stage: 'DEIDENTIFY', label: '비식별화', status: 'COMPLETED', progressPercent: 100 },
        { stage: 'YOLO', label: 'YOLO', status: 'IN_PROGRESS', progressPercent: 60 },
        { stage: 'SAM2', label: 'SAM2', status: 'PENDING', progressPercent: 0 },
        { stage: 'VLM_VERIFY', label: 'VLM 검증', status: 'PENDING', progressPercent: 0 },
      ],
    },
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

describe('VideoStatusPage', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    setWorker();
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  it('처리_현황_5단계_progressbar_렌더', async () => {
    mock.onGet('/batch/status').reply(200, {
      success: true,
      data: samplePayload,
      message: null,
      errorCode: null,
    });

    renderWithProviders(<VideoStatusPage />);

    await waitFor(() => {
      expect(screen.getByText('테헤란로 CCTV')).toBeInTheDocument();
    });

    const bars = screen.getAllByRole('progressbar');
    expect(bars).toHaveLength(5);
  });

  it('영상_없을_때_EmptyState_노출', async () => {
    mock.onGet('/batch/status').reply(200, {
      success: true,
      data: { ...samplePayload, videos: [] },
      message: null,
      errorCode: null,
    });

    renderWithProviders(<VideoStatusPage />);

    await waitFor(() => {
      expect(screen.getByText('처리 중인 영상이 없습니다')).toBeInTheDocument();
    });
  });

  it('처리_KPI_3종_노출', async () => {
    mock.onGet('/batch/status').reply(200, {
      success: true,
      data: samplePayload,
      message: null,
      errorCode: null,
    });

    renderWithProviders(<VideoStatusPage />);

    await waitFor(() => {
      expect(screen.getByText('처리 중')).toBeInTheDocument();
    });
    expect(screen.getByText('완료')).toBeInTheDocument();
    expect(screen.getByText('실패')).toBeInTheDocument();
  });
});
