// SCR-REVIEW-002 Phase 2 — ReviewPage 3분할 레이아웃 통합 검증.
//
// 기존 ReviewPage.test.tsx 는 그대로 두고 Phase 2 신규 케이스만 추가.

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

// jsdom 환경에서 konva 가 native canvas 모듈을 요구하므로 mock 으로 대체.
vi.mock('react-konva', async () => (await import('@/test/konvaMock')).createKonvaMock());

import { apiClient } from '@/lib/api/client';
import { renderWithProviders } from '@/test/renderWithProviders';

import { ReviewPage } from '@/pages/ReviewPage';

const baseReview = {
  id: 10,
  videoId: 1,
  cctvName: 'CCTV-1',
  workerId: 7,
  workerName: '홍길동',
  submittedAt: '2026-05-07T01:30:00Z',
  labelCount: 12,
};

describe('ReviewPage Phase 2 — 3분할 레이아웃', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    mock.onGet('/reviews/10/issues').reply(200, {
      success: true,
      data: [],
      message: null,
      errorCode: null,
    });
    mock.onGet('/reviews/1/frames').reply(200, {
      success: true,
      data: {
        videoId: 1,
        totalFrames: 25,
        frames: [],
      },
      message: null,
      errorCode: null,
    });
  });

  afterEach(() => {
    mock.restore();
  });

  it('검수페이지_로드시_헤더에_영상명과_작업자_표시', async () => {
    mock.onGet('/reviews/10').reply(200, {
      success: true,
      data: { ...baseReview, status: 'REVIEWING' },
      message: null,
      errorCode: null,
    });

    renderWithProviders(<ReviewPage />, {
      initialEntries: ['/review/10'],
      routes: [{ path: '/review/:id', element: <ReviewPage /> }],
    });

    await waitFor(() => {
      expect(screen.getByTestId('review-header-cctv-name')).toHaveTextContent('CCTV-1');
    });
    expect(screen.getByTestId('review-header-worker-name')).toHaveTextContent('홍길동');

    // SCREEN-019 §검수 헤더 — 헤더에는 프레임 위치 표시를 두지 않는다.
    expect(screen.queryByTestId('review-header-frame-counter')).toBeNull();
    expect(screen.queryByText(/Frame\s*\d+\s*\/\s*\d+/)).toBeNull();
  });

  it('로딩중_spinner_노출', async () => {
    // 응답 지연 시뮬레이션 — 100ms 지연
    mock.onGet('/reviews/10').reply(() => {
      return new Promise((resolve) => {
        setTimeout(() => {
          resolve([
            200,
            {
              success: true,
              data: { ...baseReview, status: 'REVIEWING' },
              message: null,
              errorCode: null,
            },
          ]);
        }, 100);
      });
    });

    renderWithProviders(<ReviewPage />, {
      initialEntries: ['/review/10'],
      routes: [{ path: '/review/:id', element: <ReviewPage /> }],
    });

    // 초기 로딩 상태
    expect(screen.getByTestId('review-page-loading')).toBeInTheDocument();
  });

  it('에러_상태_안내_표시', async () => {
    mock.onGet('/reviews/10').reply(500, {
      success: false,
      data: null,
      message: '서버 오류',
      errorCode: 'INTERNAL_SERVER_ERROR',
    });

    renderWithProviders(<ReviewPage />, {
      initialEntries: ['/review/10'],
      routes: [{ path: '/review/:id', element: <ReviewPage /> }],
    });

    await waitFor(() => {
      expect(screen.getByText('검수 정보를 불러올 수 없습니다')).toBeInTheDocument();
    });
  });

  it('3분할_레이아웃_요소_존재', async () => {
    mock.onGet('/reviews/10').reply(200, {
      success: true,
      data: { ...baseReview, status: 'REVIEWING' },
      message: null,
      errorCode: null,
    });

    renderWithProviders(<ReviewPage />, {
      initialEntries: ['/review/10'],
      routes: [{ path: '/review/:id', element: <ReviewPage /> }],
    });

    await waitFor(() => {
      expect(screen.getByTestId('review-header')).toBeInTheDocument();
    });
    expect(screen.getByTestId('review-canvas-readonly')).toBeInTheDocument();
    // Phase 3 — placeholder 가 LabelCanvas 로 교체됨
    expect(screen.getByTestId('review-label-canvas')).toBeInTheDocument();
    // Phase 4 — aside placeholder 가 객체 목록/속성 패널로 교체됨
    expect(screen.getByTestId('review-aside')).toBeInTheDocument();
    expect(screen.getByTestId('review-aside-object-list')).toBeInTheDocument();
    expect(screen.getByTestId('review-aside-attributes')).toBeInTheDocument();
    expect(screen.getByTestId('review-timeline-placeholder')).toBeInTheDocument();

    // SCREEN-019 — 승인·반려 진입점은 헤더 단독. 하단 액션 바는 두지 않는다.
    expect(screen.queryByTestId('review-action-bar')).toBeNull();
    const header = screen.getByTestId('review-header');
    expect(header).toContainElement(screen.getByTestId('review-action-approve'));
    expect(header).toContainElement(screen.getByTestId('review-action-reject'));
  });

  it('검수상태_COMPLETED_시_액션버튼_disabled', async () => {
    mock.onGet('/reviews/10').reply(200, {
      success: true,
      data: { ...baseReview, status: 'COMPLETED' },
      message: null,
      errorCode: null,
    });

    renderWithProviders(<ReviewPage />, {
      initialEntries: ['/review/10'],
      routes: [{ path: '/review/:id', element: <ReviewPage /> }],
    });

    await waitFor(() => {
      expect(screen.getByTestId('review-action-approve')).toBeDisabled();
    });
    expect(screen.getByTestId('review-action-reject')).toBeDisabled();
  });

  // 승인·반려가 헤더로 이관된 뒤에도 반려 사유 입력·검증(1~1000자)이 그대로 동작해야 한다.
  it('헤더_반려_클릭시_사유_입력창과_검증이_유지된다', async () => {
    mock.onGet('/reviews/10').reply(200, {
      success: true,
      data: { ...baseReview, status: 'REVIEWING' },
      message: null,
      errorCode: null,
    });

    const user = userEvent.setup();
    renderWithProviders(<ReviewPage />, {
      initialEntries: ['/review/10'],
      routes: [{ path: '/review/:id', element: <ReviewPage /> }],
    });

    const rejectBtn = await screen.findByTestId('review-action-reject');
    await user.click(rejectBtn);

    const reasonTextarea = await screen.findByLabelText(/반려 사유/);
    const submitBtn = screen.getByRole('button', { name: '반려 확정' });

    // 사유 미입력이면 제출 불가.
    expect(submitBtn).toBeDisabled();

    await user.type(reasonTextarea, '재작업 필요');
    await waitFor(() => {
      expect(submitBtn).not.toBeDisabled();
    });
  });

  // 승인은 곧바로 처리되지 않고 확정 확인창을 한 번 거친다.
  it('헤더_승인_클릭시_승인_확정_확인창이_뜬다', async () => {
    mock.onGet('/reviews/10').reply(200, {
      success: true,
      data: { ...baseReview, status: 'REVIEWING' },
      message: null,
      errorCode: null,
    });

    const user = userEvent.setup();
    renderWithProviders(<ReviewPage />, {
      initialEntries: ['/review/10'],
      routes: [{ path: '/review/:id', element: <ReviewPage /> }],
    });

    const approveBtn = await screen.findByTestId('review-action-approve');
    await user.click(approveBtn);

    expect(await screen.findByRole('button', { name: '승인 확정' })).toBeInTheDocument();
  });
});
