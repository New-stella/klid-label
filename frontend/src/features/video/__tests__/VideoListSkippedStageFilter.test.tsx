// 영상 목록 — 「시계열 건너뜀」 검색·필터. [@design SCREEN-008] [@design API-042] [@design ADR-050]
//
// ★ 왜 이 필터가 필요한가 — 벤더 연동이 확정된 뒤 건너뛴 영상을 모아 되살리려면 그 대상을
//   목록에서 골라낼 수 있어야 한다. 일괄 요청이 한 번에 받는 건수에 상한이 있어, 필터가 없으면
//   회수 자체가 성립하지 않는다.
//
// ★ 하위호환이 이 파일의 핵심이다 — 미선택이면 파라미터를 **아예 싣지 않는다**. 빈 문자열을
//   올리면 서버가 그것을 값으로 해석할 여지가 생기고, 기존 북마크·저장된 URL 의 동작이 달라진다.

import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { apiClient } from '@/lib/api/client';
import { VideoListPage } from '@/pages/VideoListPage';
import { useAuthStore } from '@/stores/useAuthStore';
import { renderWithProviders } from '@/test/renderWithProviders';

function mockVideos(mock: MockAdapter) {
  mock.onGet('/videos').reply(200, {
    success: true,
    data: {
      content: [
        {
          id: 1,
          cctvName: 'CCTV-1',
          vmsClipId: 'VMS-1',
          eventName: '쓰러짐',
          eventTypeCd: 'FALL',
          frameCount: 900,
          status: 'COMPLETED',
          capturedAt: '2026-05-01T12:00:00Z',
        },
      ],
      totalElements: 1,
      totalPages: 1,
      number: 0,
      size: 20,
    },
    message: null,
    errorCode: null,
  });
}

/** 마지막 목록 조회 요청의 쿼리 파라미터. */
function lastListParams(mock: MockAdapter): Record<string, unknown> {
  const calls = mock.history.get.filter((h) => h.url === '/videos');
  expect(calls.length).toBeGreaterThan(0);
  return (calls[calls.length - 1]!.params ?? {}) as Record<string, unknown>;
}

describe('영상 목록 시계열 건너뜀 필터', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    mock.onGet('/manage/configs').reply(200, { success: true, data: [], message: null, errorCode: null });
    mock.onGet('/event-types').reply(200, { success: true, data: [], message: null, errorCode: null });
    useAuthStore.setState({
      token: 'dummy-test-token',
      claims: {
        sub: 'u-1',
        role: 'REVIEWER',
        channel: 'INTERNAL',
        exp: Math.floor(Date.now() / 1000) + 3600,
      },
    });
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  it('미선택이면_목록_요청에_skippedStage_파라미터가_실리지_않는다', async () => {
    mockVideos(mock);
    renderWithProviders(<VideoListPage />, { initialEntries: ['/video/status'] });

    await waitFor(() => expect(screen.getByText('CCTV-1')).toBeInTheDocument());
    expect(lastListParams(mock)).not.toHaveProperty('skippedStage');
  });

  it('시계열_건너뜀을_고르고_조회하면_skippedStage_VLM_이_실린다', async () => {
    mockVideos(mock);
    const user = userEvent.setup();
    renderWithProviders(<VideoListPage />, { initialEntries: ['/video/status'] });
    await waitFor(() => expect(screen.getByText('CCTV-1')).toBeInTheDocument());

    await user.click(screen.getByLabelText('시계열 건너뜀'));
    await user.click(await screen.findByRole('option', { name: '시계열 건너뜀' }));
    await user.click(screen.getByRole('button', { name: '조회' }));

    await waitFor(() => expect(lastListParams(mock).skippedStage).toBe('VLM'));
  });

  it('URL_에_들어온_skippedStage_를_그대로_조회에_싣는다', async () => {
    mockVideos(mock);
    renderWithProviders(<VideoListPage />, {
      initialEntries: ['/video/status?skippedStage=VLM'],
    });

    await waitFor(() => expect(screen.getByText('CCTV-1')).toBeInTheDocument());
    expect(lastListParams(mock).skippedStage).toBe('VLM');
  });

  it('미지의_값은_버린다_경로_세그먼트가_아니어도_서버로_흘려보내지_않는다', async () => {
    mockVideos(mock);
    renderWithProviders(<VideoListPage />, {
      initialEntries: ['/video/status?skippedStage=%27%20OR%201%3D1'],
    });

    await waitFor(() => expect(screen.getByText('CCTV-1')).toBeInTheDocument());
    expect(lastListParams(mock)).not.toHaveProperty('skippedStage');
  });

  it('초기화하면_필터가_풀려_파라미터가_사라진다', async () => {
    mockVideos(mock);
    const user = userEvent.setup();
    renderWithProviders(<VideoListPage />, {
      initialEntries: ['/video/status?skippedStage=VLM'],
    });
    await waitFor(() => expect(screen.getByText('CCTV-1')).toBeInTheDocument());

    await user.click(screen.getByRole('button', { name: '초기화' }));

    await waitFor(() => expect(lastListParams(mock)).not.toHaveProperty('skippedStage'));
  });
});
