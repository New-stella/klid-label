import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { apiClient } from '@/lib/api/client';
import { ReviewListPage } from '@/pages/ReviewListPage';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';

describe('ReviewListPage', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    useAuthStore.setState({
      token: 'tok',
      claims: { sub: 'u', role: 'REVIEWER', channel: 'INTERNAL', exp: 9999999999 },
    });
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  it('검수_대기_목록_DataTable_렌더링_및_검수_시작_버튼_네비게이션', async () => {
    mock.onGet('/reviews').reply(200, {
      success: true,
      data: {
        content: [
          {
            id: 10,
            videoId: 1,
            cctvName: 'CCTV-A',
            workerId: 7,
            workerName: '홍길동',
            submittedAt: '2026-05-07T10:00:00Z',
            labelCount: 12,
            status: 'REVIEW_PENDING',
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
    mock.onGet('/videos').reply(200, {
      success: true,
      data: {
        content: [
          {
            id: 1,
            cctvName: 'CCTV-A',
            vmsClipId: 'VMS-0001',
            eventName: '쓰러짐',
            eventTypeCd: 'FALL',
            localGov: '서울시',
            frameCount: 30,
            status: 'COMPLETED',
            capturedAt: '2026-05-07T10:00:00Z',
          },
        ],
        totalElements: 1,
        totalPages: 1,
        number: 0,
        size: 999,
      },
      message: null,
      errorCode: null,
    });

    const user = userEvent.setup();
    renderWithProviders(<ReviewListPage />, {
      initialEntries: ['/review'],
      routes: [
        { path: '/review', element: <ReviewListPage /> },
        { path: '/review/:id', element: <div>REVIEW_PAGE_:id</div> },
      ],
    });

    await waitFor(() => {
      expect(screen.getByText('CCTV-A')).toBeInTheDocument();
    });
    expect(screen.getByText('홍길동')).toBeInTheDocument();
    expect(screen.getByText('12')).toBeInTheDocument();

    // 작업 목록과 동일한 2줄 시각 정합 — 영상 식별자(video-NNNN) 노출
    expect(screen.getByText('video-0001')).toBeInTheDocument();
    // 이벤트 컬럼 — videos left-join 으로 EventTypeBadge 렌더
    expect(screen.getByText('쓰러짐')).toBeInTheDocument();

    const startBtn = screen.getByRole('button', { name: /검수 시작 CCTV-A/ });
    await user.click(startBtn);

    await waitFor(() => {
      expect(screen.getByText('REVIEW_PAGE_:id')).toBeInTheDocument();
    });
  });

  it('이벤트_메타_없는_영상은_대시_폴백', async () => {
    mock.onGet('/reviews').reply(200, {
      success: true,
      data: {
        content: [
          {
            id: 11,
            videoId: 2,
            cctvName: 'CCTV-B',
            workerId: 7,
            workerName: '김작업',
            submittedAt: '2026-05-07T10:00:00Z',
            labelCount: 5,
            status: 'REVIEW_PENDING',
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
    // useVideos 결과에 매칭되는 영상 없음 — 이벤트 컬럼은 '-' 폴백
    mock.onGet('/videos').reply(200, {
      success: true,
      data: {
        content: [],
        totalElements: 0,
        totalPages: 0,
        number: 0,
        size: 999,
      },
      message: null,
      errorCode: null,
    });

    renderWithProviders(<ReviewListPage />, { initialEntries: ['/review'] });

    await waitFor(() => {
      expect(screen.getByText('CCTV-B')).toBeInTheDocument();
    });
    // 영상 식별자는 그대로 노출 (videoId 기반)
    expect(screen.getByText('video-0002')).toBeInTheDocument();
    // 이벤트 컬럼: video 메타 없음 → '-' 폴백
    const row = screen.getByText('CCTV-B').closest('tr');
    expect(row).not.toBeNull();
    expect(within(row as HTMLElement).getByText('-')).toBeInTheDocument();
  });

  it('빈_목록일_때_EmptyState_노출', async () => {
    mock.onGet('/reviews').reply(200, {
      success: true,
      data: { content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 },
      message: null,
      errorCode: null,
    });
    mock.onGet('/videos').reply(200, {
      success: true,
      data: { content: [], totalElements: 0, totalPages: 0, number: 0, size: 999 },
      message: null,
      errorCode: null,
    });

    renderWithProviders(<ReviewListPage />, { initialEntries: ['/review'] });

    await waitFor(() => {
      expect(screen.getByText('검수 대기 항목이 없습니다')).toBeInTheDocument();
    });
  });
});
