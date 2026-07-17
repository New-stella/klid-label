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

  it('검수_대기_목록_DataTable_렌더링_및_검수_시작_버튼_네비게이션_eventName_BE_직접_사용', async () => {
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
            // BE enrich: eventName/eventTypeCd 직접 응답
            eventName: '쓰러짐',
            eventTypeCd: 'EVT_FALL',
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
    // 이벤트 컬럼 — BE 응답의 eventName 으로 EventTypeBadge 렌더
    expect(screen.getByText('쓰러짐')).toBeInTheDocument();

    const startBtn = screen.getByRole('button', { name: /검수 시작 CCTV-A/ });
    await user.click(startBtn);

    await waitFor(() => {
      expect(screen.getByText('REVIEW_PAGE_:id')).toBeInTheDocument();
    });
  });

  it('이벤트_메타_없는_영상은_대시_폴백_BE_eventName_미응답시', async () => {
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
            // BE eventName 미응답 (null)
            eventName: null,
            eventTypeCd: null,
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

    renderWithProviders(<ReviewListPage />, { initialEntries: ['/review'] });

    await waitFor(() => {
      expect(screen.getByText('CCTV-B')).toBeInTheDocument();
    });
    // 영상 식별자는 그대로 노출 (videoId 기반)
    expect(screen.getByText('video-0002')).toBeInTheDocument();
    // 이벤트 컬럼: BE eventName 미응답 → '-' 폴백
    const row = screen.getByText('CCTV-B').closest('tr');
    expect(row).not.toBeNull();
    expect(within(row as HTMLElement).getByText('-')).toBeInTheDocument();
  });

  it('ReviewListPage_렌더_시_videos_API를_호출하지_않는다', async () => {
    // /videos 호출이 발생하면 mock 미설정 → axios-mock-adapter 가 404/no-handler 응답으로 fail.
    // 단언: mock.history.get 에 /videos 경로 호출이 0 건이어야 한다.
    mock.onGet('/reviews').reply(200, {
      success: true,
      data: {
        content: [
          {
            id: 12,
            videoId: 3,
            cctvName: 'CCTV-C',
            workerId: 8,
            workerName: '박작업',
            submittedAt: '2026-05-07T10:00:00Z',
            labelCount: 1,
            status: 'REVIEW_PENDING',
            eventName: '낙상',
            eventTypeCd: 'EVT_FALL',
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

    renderWithProviders(<ReviewListPage />, { initialEntries: ['/review'] });

    await waitFor(() => {
      expect(screen.getByText('CCTV-C')).toBeInTheDocument();
    });

    // /videos 경로로 GET 호출이 한 번도 발생하지 않아야 한다.
    const videoCalls = mock.history.get.filter((req) =>
      (req.url ?? '').startsWith('/videos'),
    );
    expect(videoCalls).toHaveLength(0);
  });

  it('빈_목록일_때_EmptyState_노출', async () => {
    mock.onGet('/reviews').reply(200, {
      success: true,
      data: { content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 },
      message: null,
      errorCode: null,
    });

    renderWithProviders(<ReviewListPage />, { initialEntries: ['/review'] });

    await waitFor(() => {
      expect(screen.getByText('검수요청 항목이 없습니다')).toBeInTheDocument();
    });
  });
});
