import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, within } from '@testing-library/react';
import { Route, Routes } from 'react-router-dom';

import { formatDateTime } from '@/features/review/formatDateTime';
import { apiClient } from '@/lib/api/client';
import { AugmentResultPage } from '@/pages/AugmentResultPage';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';

/**
 * 작업 요약의 **요청일시** 회귀 가드.
 *
 * 이 칸은 값을 공급하는 API 가 없어 오랫동안 화면에 존재하지 않았다. 이제 결과 응답이 잡 단위
 * `requestedAt`(증강 행 최소 등록일시)을 내려주므로 그것을 그대로 표시한다.
 *
 * - 값이 없으면(증강 행 0건 · 이 필드를 모르는 구 응답) **지어내지 않고** `-` 를 보인다.
 * - 항목별 "결정 시각(decidedAt)" 과 축이 다르므로 그 값으로 대체하지 않는다.
 */
describe('AugmentResultPage 작업 요약 — 요청일시', () => {
  let mock: MockAdapter;

  const REQUESTED_AT = '2026-08-01T09:30:00';

  const externalItem = (id: number) => ({
    id,
    videoId: 101,
    cctvName: 'CCTV-01',
    type: 'WINTER',
    framePairs: [],
    // 결정 시각은 요청일시와 **다른 축**이다 — 요약이 이 값을 집어오면 안 된다.
    decision: 'ACCEPTED',
    decidedAt: '2026-07-30T10:00:00',
    rejectReason: null,
    derivativeRawSn: null,
    totalFramePairs: 0,
    reviewable: false,
    prompt: null,
  });

  const mockResult = (extra: Record<string, unknown>) => {
    mock.onGet('/augments/101/result').reply(200, {
      success: true,
      data: {
        jobId: 101,
        status: 'COMPLETED',
        results: [externalItem(100)],
        message: null,
        page: 0,
        size: 12,
        itemPage: 0,
        itemSize: 20,
        totalElements: 1,
        totalPages: 1,
        ...extra,
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
    let seq = 0;
    Object.defineProperty(URL, 'createObjectURL', {
      configurable: true,
      writable: true,
      value: vi.fn(() => `blob:mock/${++seq}`),
    });
    Object.defineProperty(URL, 'revokeObjectURL', {
      configurable: true,
      writable: true,
      value: vi.fn(),
    });
    mock.onGet(/\/frames\/\d+\/deid-image$/).reply(200, new Blob(['img']));
    mock.onGet(/\/augments\/\d+\/progress$/).reply(200, {
      success: true,
      data: {
        id: 1,
        augTypeCd: 'WINTER',
        status: 'SUCCEEDED',
        progress: 100,
        unavailableReason: null,
        totalJobCount: 1,
        terminalJobCount: 1,
        cancelable: false,
        nextPollAfterMs: 0,
      },
      message: null,
      errorCode: null,
    });
    useAuthStore.setState({
      token: 'dummy-token',
      claims: { sub: 'u', role: 'REVIEWER', channel: 'INTERNAL', exp: 9999999999 },
    });
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  it('응답의_요청일시를_요약에_표시한다', async () => {
    // given
    mockResult({ requestedAt: REQUESTED_AT });

    // when
    renderPage();

    // then
    const summary = await screen.findByTestId('augment-result-summary');
    expect(within(summary).getByText('요청일시')).toBeInTheDocument();
    const cell = screen.getByTestId('augment-result-requested-at');
    expect(cell).toHaveTextContent(formatDateTime(REQUESTED_AT));
    expect(cell.textContent ?? '').toContain('2026');
  });

  it('요청일시가_null_이면_대시를_보인다', async () => {
    // given — 증강 행 0건 등으로 BE 가 값을 만들 근거가 없는 경우. 지어내지 않는다.
    mockResult({ requestedAt: null });

    // when
    renderPage();

    // then
    await screen.findByTestId('augment-result-summary');
    expect(screen.getByTestId('augment-result-requested-at')).toHaveTextContent('-');
  });

  it('요청일시_필드가_없는_구_응답에서도_대시를_보인다', async () => {
    // given — 이 필드를 모르는 구 서버 응답(하위호환). 결정 시각으로 대체하지 않는다.
    mockResult({});

    // when
    renderPage();

    // then
    await screen.findByTestId('augment-result-summary');
    const cell = screen.getByTestId('augment-result-requested-at');
    expect(cell).toHaveTextContent('-');
    expect(cell.textContent ?? '').not.toContain('2026');
  });
});
