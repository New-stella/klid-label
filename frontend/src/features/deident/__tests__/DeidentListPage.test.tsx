import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor } from '@testing-library/react';

import { apiClient } from '@/lib/api/client';
import { DeidentListPage } from '@/pages/DeidentListPage';
import { useAuthStore } from '@/stores/useAuthStore';
import { renderWithProviders } from '@/test/renderWithProviders';

const sampleRows = [
  {
    videoId: 1,
    cctvName: '강남대로 CCTV',
    vmsClipId: 'VMS-1',
    prvcType: 'PRVC',
    prvcYn: 'Y',
    status: 'COMPLETED',
    totalFrames: 900,
    processedFrames: 900,
    failedFrames: 0,
    capturedAt: '2026-05-01T10:00:00Z',
  },
  {
    videoId: 2,
    cctvName: '익명 카메라',
    vmsClipId: 'VMS-2',
    prvcType: 'ANONY',
    prvcYn: 'N',
    status: 'COMPLETED',
    totalFrames: 600,
    processedFrames: 600,
    failedFrames: 0,
    capturedAt: '2026-05-02T10:00:00Z',
  },
];

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

describe('DeidentListPage', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    mock.onGet('/deident').reply(200, {
      success: true,
      data: {
        content: sampleRows,
        totalElements: 2,
        totalPages: 1,
        number: 0,
        size: 20,
      },
      message: null,
      errorCode: null,
    });
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  it('비식별_목록_PRVC_YN_N_행은_액션_미노출', async () => {
    setRole('REVIEWER');
    renderWithProviders(<DeidentListPage />, {
      initialEntries: ['/deident'],
      routes: [{ path: '/deident', element: <DeidentListPage /> }],
    });

    await waitFor(() => {
      expect(screen.getByText('강남대로 CCTV')).toBeInTheDocument();
    });
    // PRVC_YN=Y → 재처리 버튼 노출
    expect(screen.getByTestId('reprocess-btn-1')).toBeInTheDocument();
    // PRVC_YN=N → 재처리 버튼 미노출
    expect(screen.queryByTestId('reprocess-btn-2')).not.toBeInTheDocument();
  });

  it('WORKER는_재처리_버튼_미노출', async () => {
    setRole('WORKER');
    renderWithProviders(<DeidentListPage />, {
      initialEntries: ['/deident'],
      routes: [{ path: '/deident', element: <DeidentListPage /> }],
    });

    await waitFor(() => {
      expect(screen.getByText('강남대로 CCTV')).toBeInTheDocument();
    });
    expect(screen.queryByTestId('reprocess-btn-1')).not.toBeInTheDocument();
  });

  it('PRVC_YN_필터_동작', async () => {
    setRole('REVIEWER');
    renderWithProviders(<DeidentListPage />, {
      initialEntries: ['/deident?prvcYn=Y'],
      routes: [{ path: '/deident', element: <DeidentListPage /> }],
    });

    await waitFor(() => {
      const last = mock.history.get[mock.history.get.length - 1];
      expect(last?.params).toMatchObject({ prvcYn: 'Y' });
    });
  });
});
