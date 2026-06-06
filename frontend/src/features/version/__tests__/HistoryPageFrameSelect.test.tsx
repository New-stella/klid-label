// R6-C 회귀 — /history/:videoId 가 videoId 를 srcSn 으로 오용하지 않고,
// videoId 로 프레임 목록을 조회해 프레임 선택 UI + 첫 프레임 이력을 렌더하는지 검증.
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { fireEvent, screen, waitFor } from '@testing-library/react';
import MockAdapter from 'axios-mock-adapter';

import { apiClient } from '@/lib/api/client';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';

import { HistoryPage } from '@/pages/HistoryPage';

function setReviewer() {
  useAuthStore.setState({
    token: 'tok',
    claims: { sub: 'u-7', role: 'REVIEWER', channel: 'INTERNAL', exp: 9999999999 },
  });
}

// videoId=4 영상은 프레임 2개(srcSn 41, 42)를 가진다.
const framesPayload = {
  success: true,
  data: {
    videoId: 4,
    totalFrames: 2,
    frames: [
      { srcSn: 41, frameNo: 0, imageUrl: '/img/41', labels: [] },
      { srcSn: 42, frameNo: 5, imageUrl: '/img/42', labels: [] },
    ],
  },
  message: null,
  errorCode: null,
};

function versionsPayload(srcSn: number) {
  return {
    success: true,
    data: [
      {
        commitSha: `c${srcSn}aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa`,
        shortHash: `c${srcSn}`,
        authorName: '홍길동',
        message: '라벨 수정',
        committedAt: '2026-05-07T10:00:00Z',
        isCurrent: true,
      },
    ],
    message: null,
    errorCode: null,
  };
}

describe('HistoryPage 프레임 선택 (R6-C)', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    setReviewer();
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  it('videoId로_프레임목록_조회후_프레임_선택_UI와_첫프레임_이력_렌더', async () => {
    // given — videoId=4 영상의 프레임 목록 + 각 프레임 버전
    mock.onGet('/reviews/4/frames').reply(200, framesPayload);
    mock.onGet('/frames/41/versions').reply(200, versionsPayload(41));
    mock.onGet('/frames/42/versions').reply(200, versionsPayload(42));

    // when — /history/4 진입
    renderWithProviders(<HistoryPage />, {
      initialEntries: ['/history/4'],
      routes: [{ path: '/history/:videoId', element: <HistoryPage /> }],
    });

    // then — 프레임 선택 UI 노출 + 첫 프레임(srcSn 41) 이력 자동 렌더 (조용한 0건 아님)
    await waitFor(() => {
      expect(screen.getByTestId('history-frame-select')).toBeInTheDocument();
    });
    await waitFor(() => {
      expect(screen.getByTestId('commit-row-c41')).toBeInTheDocument();
    });
  });

  it('프레임_전환시_선택프레임의_이력으로_갱신', async () => {
    mock.onGet('/reviews/4/frames').reply(200, framesPayload);
    mock.onGet('/frames/41/versions').reply(200, versionsPayload(41));
    mock.onGet('/frames/42/versions').reply(200, versionsPayload(42));

    renderWithProviders(<HistoryPage />, {
      initialEntries: ['/history/4'],
      routes: [{ path: '/history/:videoId', element: <HistoryPage /> }],
    });

    await waitFor(() => {
      expect(screen.getByTestId('commit-row-c41')).toBeInTheDocument();
    });

    // 두 번째 프레임(srcSn 42)으로 전환
    fireEvent.change(screen.getByTestId('history-frame-select'), {
      target: { value: '42' },
    });

    await waitFor(() => {
      expect(screen.getByTestId('commit-row-c42')).toBeInTheDocument();
    });
  });
});
