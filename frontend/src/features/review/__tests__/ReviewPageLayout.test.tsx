// SCR-REVIEW-002 Phase 2 — ReviewPage 3분할 레이아웃 통합 검증.
//
// 기존 ReviewPage.test.tsx 는 그대로 두고 Phase 2 신규 케이스만 추가.

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor } from '@testing-library/react';

// jsdom 환경에서 konva 가 native canvas 모듈을 요구하므로 mock 으로 대체.
vi.mock('react-konva', () => {
  // eslint-disable-next-line @typescript-eslint/no-var-requires
  const React = require('react');
  const passthrough = (name: string) => {
    // eslint-disable-next-line react/display-name, @typescript-eslint/no-explicit-any
    return ({ children, image: _image, ...rest }: any) =>
      React.createElement('div', { 'data-konva': name, ...rest }, children);
  };
  return {
    Stage: passthrough('Stage'),
    Layer: passthrough('Layer'),
    Image: passthrough('Image'),
    Rect: passthrough('Rect'),
    Line: passthrough('Line'),
    Circle: passthrough('Circle'),
    Group: passthrough('Group'),
  };
});

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
      expect(screen.getByTestId('review-header-cctv-name')).toHaveTextContent(
        'CCTV-1',
      );
    });
    expect(screen.getByTestId('review-header-worker-name')).toHaveTextContent(
      '홍길동',
    );

    // 프레임 카운터는 frames 조회 후 표시 (totalFrames=25)
    await waitFor(() => {
      expect(screen.getByTestId('review-header-frame-counter')).toHaveTextContent(
        'Frame 1/25',
      );
    });
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
      expect(
        screen.getByText('검수 정보를 불러올 수 없습니다'),
      ).toBeInTheDocument();
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
    expect(screen.getByTestId('review-action-bar')).toBeInTheDocument();
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
});
