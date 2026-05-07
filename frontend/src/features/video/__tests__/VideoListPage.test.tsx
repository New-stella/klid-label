import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { apiClient } from '@/lib/api/client';
import { VideoListPage } from '@/pages/VideoListPage';
import { useAuthStore } from '@/stores/useAuthStore';
import { renderWithProviders } from '@/test/renderWithProviders';

function mockVideosOnce(mock: MockAdapter, opts: { content?: unknown[]; total?: number } = {}) {
  mock.onGet('/videos').reply(200, {
    success: true,
    data: {
      content: opts.content ?? [
        {
          id: 1,
          cctvName: '강남대로 CCTV',
          vmsClipId: 'VMS-1',
          eventName: '화재',
          eventTypeCd: 'FIRE',
          localGov: '강남구',
          frameCount: 900,
          status: 'COMPLETED',
          capturedAt: '2026-05-01T12:00:00Z',
        },
      ],
      totalElements: opts.total ?? 1,
      totalPages: 1,
      number: 0,
      size: 20,
    },
    message: null,
    errorCode: null,
  });
}

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

describe('VideoListPage', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  it('영상_목록_렌더_및_REVIEWER_배정_버튼_노출', async () => {
    setRole('REVIEWER');
    mockVideosOnce(mock);

    renderWithProviders(<VideoListPage />, { initialEntries: ['/video/completed'] });

    await waitFor(() => {
      expect(screen.getByText('강남대로 CCTV')).toBeInTheDocument();
    });
    expect(screen.getByRole('button', { name: '배정' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /배경영상 요청/ })).toBeInTheDocument();
  });

  it('WORKER는_배정_배경영상_요청_버튼_미노출', async () => {
    setRole('WORKER');
    mockVideosOnce(mock);

    renderWithProviders(<VideoListPage />, { initialEntries: ['/video/completed'] });

    await waitFor(() => {
      expect(screen.getByText('강남대로 CCTV')).toBeInTheDocument();
    });
    expect(screen.queryByRole('button', { name: '배정' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /배경영상 요청/ })).not.toBeInTheDocument();
  });

  it('영상_없을_때_EmptyState_노출', async () => {
    setRole('WORKER');
    mockVideosOnce(mock, { content: [], total: 0 });

    renderWithProviders(<VideoListPage />, { initialEntries: ['/video/completed'] });

    await waitFor(() => {
      expect(screen.getByText('조건에 맞는 영상이 없습니다')).toBeInTheDocument();
    });
  });

  it('loading_상태에서_DataTable_skeleton_렌더', () => {
    setRole('WORKER');
    // never resolve to keep loading
    mock.onGet('/videos').reply(() => new Promise(() => {}));

    renderWithProviders(<VideoListPage />, { initialEntries: ['/video/completed'] });

    // EmptyState 미노출 (loading)
    expect(screen.queryByText('조건에 맞는 영상이 없습니다')).not.toBeInTheDocument();
  });

  it('영상_목록_검색_필터_URL_파라미터_동기화', async () => {
    setRole('WORKER');
    mockVideosOnce(mock, { content: [], total: 0 });

    const user = userEvent.setup();
    renderWithProviders(<VideoListPage />, { initialEntries: ['/video/completed'] });

    await waitFor(() => {
      expect(screen.getByLabelText('CCTV명/이벤트 검색')).toBeInTheDocument();
    });

    // 검색어 입력 후 검색
    const input = screen.getByLabelText('CCTV명/이벤트 검색');
    await user.type(input, '강남');
    await user.click(screen.getByRole('button', { name: '검색' }));

    // 마지막 호출 params에 cctvNameKeyword가 포함됨을 확인
    await waitFor(() => {
      const last = mock.history.get[mock.history.get.length - 1];
      expect(last?.params).toMatchObject({ cctvNameKeyword: '강남' });
    });
  });
});
