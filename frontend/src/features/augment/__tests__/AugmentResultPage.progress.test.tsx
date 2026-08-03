import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor, within } from '@testing-library/react';
import { Route, Routes } from 'react-router-dom';

import { apiClient } from '@/lib/api/client';
import { AugmentResultPage } from '@/pages/AugmentResultPage';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';

/**
 * Phase 5 (A·B) — 진행률은 **서버 실값**(GET /v1/augments/{id}/progress)이어야 한다.
 *
 * 구 결함: 화면이 `status === 'COMPLETED' ? 100 : PROCESSING ? 50 : 0` 으로 가짜 진행률을 그렸다.
 * 또 `unavailableReason` 4값(NOOP/TRANSIENT_ERROR/AWAITING_ACK/QUERY_LIMIT_EXCEEDED)은 성격이
 * 전혀 달라 같은 모양으로 표시하면 정상(NOOP)이 오류로, 진짜 장애가 정상으로 위장된다.
 */
describe('AugmentResultPage 진행률(서버 실값)', () => {
  let mock: MockAdapter;
  const AUG_ID = 7;

  const externalItem = () => ({
    id: AUG_ID,
    videoId: 101,
    cctvName: 'CCTV-01',
    type: 'WINTER',
    framePairs: [],
    decision: 'PENDING',
    decidedAt: null,
    rejectReason: null,
    derivativeRawSn: null,
    totalFramePairs: 0,
    reviewable: true,
    prompt: null,
  });

  const mockResult = (status = 'PROCESSING') => {
    mock.onGet('/augments/101/result').reply(200, {
      success: true,
      data: {
        jobId: 101,
        status,
        results: [externalItem()],
        message: null,
        page: 0,
        size: 12,
        itemPage: 0,
        itemSize: 20,
        totalElements: 1,
        totalPages: 1,
      },
      message: null,
      errorCode: null,
    });
  };

  const mockProgress = (data: Record<string, unknown>) => {
    mock.onGet(`/augments/${AUG_ID}/progress`).reply(200, {
      success: true,
      data: {
        id: AUG_ID,
        augTypeCd: 'WINTER',
        totalJobCount: 2,
        terminalJobCount: 0,
        cancelable: false,
        nextPollAfterMs: 30_000,
        ...data,
      },
      message: null,
      errorCode: null,
    });
  };

  const renderPage = () =>
    renderWithProviders(
      <Routes>
        <Route path="/augment/result/:jobId" element={<AugmentResultPage />} />
      </Routes>,
      { initialEntries: ['/augment/result/101'] },
    );

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    useAuthStore.setState({
      token: 'dummy-token',
      claims: { sub: 'u', role: 'REVIEWER', channel: 'INTERNAL', exp: 9999999999 },
    });
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  it('진행률은_서버_progress_값을_표시한다', async () => {
    // given
    mockResult('PROCESSING');
    mockProgress({
      status: 'RUNNING',
      progress: 37,
      unavailableReason: null,
      nextPollAfterMs: 3000,
    });

    // when
    renderPage();

    // then — 서버가 준 37% 가 그대로 표시된다
    await waitFor(() => {
      expect(screen.getByTestId('augment-progress-value')).toHaveTextContent('37%');
    });
    const bar = within(screen.getByTestId(`augment-progress-${AUG_ID}`)).getByRole(
      'progressbar',
    );
    expect(bar).toHaveAttribute('aria-valuenow', '37');
  });

  it('하드코딩된_50퍼센트가_더는_나타나지_않는다', async () => {
    // given — 구 코드라면 status=PROCESSING 이므로 화면에 50% 가 그려졌다
    mockResult('PROCESSING');
    mockProgress({
      status: 'RUNNING',
      progress: 37,
      unavailableReason: null,
      nextPollAfterMs: 3000,
    });

    // when
    renderPage();

    // then
    await waitFor(() => {
      expect(screen.getByTestId('augment-progress-value')).toBeInTheDocument();
    });
    expect(document.body.textContent).not.toContain('50%');
  });

  it('unavailableReason_NOOP_은_오류가_아니라_미연동으로_표시된다', async () => {
    // given — dev/stg/prd 기본이 noop 이라 배포 환경 대부분이 이 경로다
    mockResult('PROCESSING');
    mockProgress({
      status: 'RECEIVED',
      progress: null,
      unavailableReason: 'NOOP',
      nextPollAfterMs: 30_000,
    });

    // when
    renderPage();

    // then — 진행률 바를 숨기고 미연동 안내만 표시. 오류(alert)로 표시하지 않는다.
    const notice = await screen.findByTestId('augment-progress-unavailable');
    expect(notice).toHaveAttribute('data-reason', 'NOOP');
    expect(notice).toHaveTextContent('외부 증강 시스템 미연동');
    expect(notice).not.toHaveAttribute('role', 'alert');
    expect(screen.queryByTestId('augment-progress-value')).not.toBeInTheDocument();
    expect(
      within(screen.getByTestId(`augment-progress-${AUG_ID}`)).queryByRole('progressbar'),
    ).not.toBeInTheDocument();
    // 실패/오류 문구가 새면 안 된다
    expect(notice.textContent ?? '').not.toContain('오류');
    expect(notice.textContent ?? '').not.toContain('실패');
  });

  it('unavailableReason_별로_다른_안내가_나온다', async () => {
    // given / when / then — 사유마다 문구가 달라야 한다(같은 모양이면 장애가 정상으로 위장된다)
    const cases: { reason: string; contains: string }[] = [
      { reason: 'TRANSIENT_ERROR', contains: '일시적으로 진행률을 가져오지 못했습니다' },
      { reason: 'AWAITING_ACK', contains: '접수 확인 중' },
      { reason: 'QUERY_LIMIT_EXCEEDED', contains: '조회 대상이 많아' },
    ];
    const seen = new Set<string>();

    for (const c of cases) {
      mock.resetHandlers();
      mockResult('PROCESSING');
      mockProgress({
        status: 'RUNNING',
        progress: null,
        unavailableReason: c.reason,
        nextPollAfterMs: 10_000,
      });

      const view = renderPage();
      const notice = await screen.findByTestId('augment-progress-unavailable');
      expect(notice).toHaveAttribute('data-reason', c.reason);
      expect(notice).toHaveTextContent(c.contains);
      seen.add(notice.textContent ?? '');
      view.unmount();
    }

    // 3사유의 문구가 서로 겹치지 않는다
    expect(seen.size).toBe(cases.length);
  });

  it('TRANSIENT_ERROR_는_작업이_계속_진행중임을_알린다', async () => {
    // given
    mockResult('PROCESSING');
    mockProgress({
      status: 'RUNNING',
      progress: null,
      unavailableReason: 'TRANSIENT_ERROR',
      nextPollAfterMs: 10_000,
    });

    // when
    renderPage();

    // then
    const notice = await screen.findByTestId('augment-progress-unavailable');
    expect(notice).toHaveTextContent('계속 진행');
  });

  it('진행률_조회_실패해도_결과_목록은_보인다', async () => {
    // given — 진행률 API 만 500
    mockResult('PROCESSING');
    mock.onGet(`/augments/${AUG_ID}/progress`).reply(500, {
      success: false,
      data: null,
      message: 'boom',
      errorCode: 'INTERNAL_ERROR',
    });

    // when
    renderPage();

    // then — 결과 항목(탭 + 결정 카드)은 정상 렌더된다
    await waitFor(() => {
      expect(screen.getByTestId('decision-card')).toBeInTheDocument();
    });
    expect(screen.getByRole('tab', { name: /겨울/ })).toBeInTheDocument();
    // 진행률 영역은 조용히 degrade (페이지 전체를 막지 않는다)
    expect(screen.queryByTestId('augment-result-page')).toBeInTheDocument();
  });

  it('해상도_파생_항목에는_진행률을_조회하지_않는다', async () => {
    // given — 해상도 파생(RESL_*)은 외부 위탁이 없어 BE 가 400 을 준다
    mock.onGet('/augments/101/result').reply(200, {
      success: true,
      data: {
        jobId: 101,
        status: 'COMPLETED',
        results: [
          {
            id: 55,
            videoId: 101,
            cctvName: 'CCTV-01',
            type: 'RESL_720P',
            framePairs: [],
            decision: 'ACCEPTED',
            decidedAt: '2026-07-28T10:00:00',
            rejectReason: null,
            derivativeRawSn: 102,
            totalFramePairs: 0,
            reviewable: false,
            prompt: null,
          },
        ],
        message: null,
        page: 0,
        size: 12,
        itemPage: 0,
        itemSize: 20,
        totalElements: 1,
        totalPages: 1,
      },
      message: null,
      errorCode: null,
    });

    // when
    renderPage();

    // then
    await waitFor(() => {
      expect(screen.getByTestId('augment-result-video-101')).toBeInTheDocument();
    });
    expect(
      mock.history.get.filter((r) => (r.url ?? '').includes('/progress')),
    ).toHaveLength(0);
  });
});
