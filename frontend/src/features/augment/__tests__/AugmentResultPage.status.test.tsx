import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor } from '@testing-library/react';
import { Route, Routes } from 'react-router-dom';

import { apiClient } from '@/lib/api/client';
import { AugmentResultPage } from '@/pages/AugmentResultPage';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';

/**
 * cudo_246 이슈3 회귀 — 증강 결과 화면은 API 응답의 실제 status(COMPLETED|FAILED|PROCESSING)를
 * 사용해야 한다. 구 버그: results.length===0 이면 무조건 PROCESSING("증강 처리 중입니다...")으로 파생.
 */
describe('AugmentResultPage 상태 표시(API status 사용)', () => {
  let mock: MockAdapter;

  const reply = (status: string) => ({
    success: true,
    data: { jobId: 10, status, results: [] },
    message: null,
    errorCode: null,
  });

  const renderPage = () =>
    renderWithProviders(
      <Routes>
        <Route path="/augment/result/:jobId" element={<AugmentResultPage />} />
      </Routes>,
      { initialEntries: ['/augment/result/10'] },
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

  it('증강상세_status_COMPLETED면_완료표시_처리중아님', async () => {
    mock.onGet('/augments/10/result').reply(200, reply('COMPLETED'));

    renderPage();

    // 완료 안내(빈 결과)만 표시되고, "처리 중" 배너는 나오지 않는다.
    await waitFor(() => {
      expect(
        screen.getByTestId('augment-result-completed-empty'),
      ).toBeInTheDocument();
    });
    expect(screen.queryByTestId('augment-result-processing')).not.toBeInTheDocument();
    expect(screen.queryByText('증강 처리 중입니다...')).not.toBeInTheDocument();
    // 상태 뱃지 = 완료(COMPLETED)
    expect(screen.getByText('완료')).toBeInTheDocument();
  });

  it('증강상세_status_FAILED면_실패표시', async () => {
    mock.onGet('/augments/10/result').reply(200, reply('FAILED'));

    renderPage();

    await waitFor(() => {
      expect(screen.getByTestId('augment-result-failed')).toBeInTheDocument();
    });
    expect(screen.getByText('증강 처리 실패')).toBeInTheDocument();
    expect(screen.queryByTestId('augment-result-processing')).not.toBeInTheDocument();
  });

  it('증강상세_status_PROCESSING일때만_처리중문구', async () => {
    mock.onGet('/augments/10/result').reply(200, reply('PROCESSING'));

    renderPage();

    await waitFor(() => {
      expect(screen.getByTestId('augment-result-processing')).toBeInTheDocument();
    });
    expect(screen.getByText('증강 처리 중입니다...')).toBeInTheDocument();
    expect(
      screen.queryByTestId('augment-result-completed-empty'),
    ).not.toBeInTheDocument();
    expect(screen.queryByTestId('augment-result-failed')).not.toBeInTheDocument();
  });
});
