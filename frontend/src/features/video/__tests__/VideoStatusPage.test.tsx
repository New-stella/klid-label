import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor } from '@testing-library/react';

import { apiClient } from '@/lib/api/client';
import { VideoStatusPage } from '@/pages/VideoStatusPage';
import { useAuthStore } from '@/stores/useAuthStore';
import { renderWithProviders } from '@/test/renderWithProviders';

// BE 응답 형식: GET /v1/batch/status → { items: BatchStageProgress[] }
const samplePayload = {
  items: [
    {
      rawSn: 1001,
      stage: 'YOLO',
      startedAt: '2026-05-07T10:00:00',
      lastUpdatedAt: '2026-05-07T10:05:00',
      retryCount: 0,
      errorMessage: null,
    },
    {
      rawSn: 1002,
      stage: 'COMPLETED',
      startedAt: '2026-05-07T09:00:00',
      lastUpdatedAt: '2026-05-07T09:30:00',
      retryCount: 0,
      errorMessage: null,
    },
    {
      rawSn: 1003,
      stage: 'FAILED',
      startedAt: '2026-05-07T08:00:00',
      lastUpdatedAt: '2026-05-07T08:10:00',
      retryCount: 2,
      errorMessage: 'IOException',
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

  it('처리_현황_원본_목록_렌더', async () => {
    mock.onGet('/batch/status').reply(200, {
      success: true,
      data: samplePayload,
      message: null,
      errorCode: null,
    });

    renderWithProviders(<VideoStatusPage />);

    // mock 정합 — 원본 번호는 '#{rawSn}' 형태로 렌더 (공백 없이)
    await waitFor(() => {
      expect(screen.getByText('#1001')).toBeInTheDocument();
    });
    expect(screen.getByText('#1002')).toBeInTheDocument();
    expect(screen.getByText('#1003')).toBeInTheDocument();
  });

  it('아이템_없을_때_EmptyState_노출', async () => {
    mock.onGet('/batch/status').reply(200, {
      success: true,
      data: { items: [] },
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

  it('KPI_카운트_계산_정확', async () => {
    // YOLO=처리중(1), COMPLETED=완료(1), FAILED=실패(1)
    mock.onGet('/batch/status').reply(200, {
      success: true,
      data: samplePayload,
      message: null,
      errorCode: null,
    });

    renderWithProviders(<VideoStatusPage />);

    await waitFor(() => {
      expect(screen.getByText('#1001')).toBeInTheDocument();
    });

    // KpiCard 는 value 를 <p> 안에 숫자 + <span>건</span> 으로 렌더 → "1" 만으로는 매치되지 않음
    // value <p> 내부의 텍스트 노드 단위로 매치하기 위해 함수형 matcher 사용.
    const onesInKpi = screen.getAllByText((_, el) => {
      if (!el || el.tagName !== 'P') return false;
      const firstChild = el.firstChild;
      return firstChild?.nodeType === Node.TEXT_NODE && firstChild.textContent?.trim() === '1';
    });
    // 처리중/완료/실패 KPI 3개의 value <p> 가 모두 '1' 을 표시.
    expect(onesInKpi.length).toBeGreaterThanOrEqual(3);
  });

  it('실패_아이템_오류메시지_노출', async () => {
    mock.onGet('/batch/status').reply(200, {
      success: true,
      data: samplePayload,
      message: null,
      errorCode: null,
    });

    renderWithProviders(<VideoStatusPage />);

    await waitFor(() => {
      expect(screen.getByText(/IOException/)).toBeInTheDocument();
    });
  });
});
