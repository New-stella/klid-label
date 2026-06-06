import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { apiClient } from '@/lib/api/client';
import { DeidentReportListPage } from '@/pages/manage/DeidentReportListPage';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';

function pageBody<T>(content: T[]) {
  return {
    success: true,
    data: {
      content,
      totalElements: content.length,
      totalPages: content.length === 0 ? 0 : 1,
      number: 0,
      size: 20,
    },
    message: null,
    errorCode: null,
  };
}

const OPEN_ROW = {
  rprtSn: 7,
  rawSn: 42,
  reporterNo: 100,
  reason: '얼굴 미블러',
  status: 'OPEN',
  reportDt: '2026-06-05T10:00:00',
  resolvedDt: null,
};

describe('DeidentReportListPage', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    useAuthStore.setState({
      token: 'tok',
      claims: { sub: '1', role: 'REVIEWER', channel: 'INTERNAL', exp: 9999999999 },
    });
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  it('OPEN_신고_목록_렌더', async () => {
    mock.onGet('/deident-reports').reply(200, pageBody([OPEN_ROW]));

    renderWithProviders(<DeidentReportListPage />);

    await waitFor(() => {
      expect(screen.getByTestId('deident-report-row-7')).toBeInTheDocument();
    });
    expect(screen.getByText('얼굴 미블러')).toBeInTheDocument();
    expect(screen.getByText('영상 #42')).toBeInTheDocument();
  });

  it('해소_처리_클릭시_resolve_API_호출_후_목록_갱신', async () => {
    let listCalls = 0;
    let resolveCalled = false;
    mock.onGet('/deident-reports').reply(() => {
      listCalls += 1;
      // 1차: OPEN 1건, resolve 후 재조회(2차): 0건
      return [200, pageBody(listCalls === 1 ? [OPEN_ROW] : [])];
    });
    mock.onPost('/deident-reports/7/resolve').reply(() => {
      resolveCalled = true;
      return [200, { success: true, data: null, message: null, errorCode: null }];
    });

    const user = userEvent.setup();
    renderWithProviders(<DeidentReportListPage />);

    await waitFor(() => {
      expect(screen.getByTestId('deident-resolve-7')).toBeInTheDocument();
    });

    await user.click(screen.getByTestId('deident-resolve-7'));

    await waitFor(() => expect(resolveCalled).toBe(true));
    // invalidate 로 목록 재조회 → 2회 이상 호출
    await waitFor(() => expect(listCalls).toBeGreaterThanOrEqual(2));
  });
});
