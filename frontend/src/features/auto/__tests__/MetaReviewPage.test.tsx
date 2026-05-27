import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { apiClient } from '@/lib/api/client';
import { MetaReviewPage } from '@/pages/MetaReviewPage';
import { useAuthStore } from '@/stores/useAuthStore';
import { renderWithProviders } from '@/test/renderWithProviders';

const metaPayload = {
  srcSn: 100,
  frameNo: 5,
  imageUrl: '/img/100.jpg',
  imageWidth: 1920,
  imageHeight: 1080,
  envMeta: { weather: 'CLEAR', timeOfDay: 'DAY', illumination: 'HIGH' },
  eventMeta: { eventTypeCd: 'FIRE', intensity: 'HIGH', description: '소화전 인근 화재' },
  stateChanges: [
    { frameNo: 3, fromState: 'NORMAL', toState: 'SUSPICIOUS', detectedAt: '2026-05-01T00:00:00Z' },
    { frameNo: 8, fromState: 'SUSPICIOUS', toState: 'EVENT', detectedAt: '2026-05-01T00:00:01Z' },
  ],
};

function setReviewer() {
  useAuthStore.setState({
    token: 'fake',
    claims: {
      sub: 'u-1',
      role: 'REVIEWER',
      channel: 'INTERNAL',
      exp: Math.floor(Date.now() / 1000) + 3600,
    },
  });
}

describe('MetaReviewPage', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    setReviewer();
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  it('메타_화면_외부_메타_폼_렌더링', async () => {
    mock.onGet('/frames/100/meta').reply(200, {
      success: true,
      data: metaPayload,
      message: null,
      errorCode: null,
    });

    renderWithProviders(<MetaReviewPage />, {
      initialEntries: ['/auto/42/meta?srcSn=100'],
      routes: [{ path: '/auto/:videoId/meta', element: <MetaReviewPage /> }],
    });

    await waitFor(() => {
      expect(screen.getByTestId('env-meta-form')).toBeInTheDocument();
    });

    expect(screen.getByTestId('env-meta-form')).toBeInTheDocument();
    expect(screen.getByTestId('event-meta-form')).toBeInTheDocument();

    // 좌·우 패널 분리
    expect(screen.getByTestId('meta-left-panel')).toBeInTheDocument();
    expect(screen.getByTestId('meta-right-panel')).toBeInTheDocument();
  });

  it('상태_변화_타임라인_렌더링', async () => {
    mock.onGet('/frames/100/meta').reply(200, {
      success: true,
      data: metaPayload,
      message: null,
      errorCode: null,
    });

    renderWithProviders(<MetaReviewPage />, {
      initialEntries: ['/auto/42/meta?srcSn=100'],
      routes: [{ path: '/auto/:videoId/meta', element: <MetaReviewPage /> }],
    });

    await waitFor(() => {
      expect(screen.getByTestId('state-change-timeline')).toBeInTheDocument();
    });
    expect(screen.getByTestId('state-change-3')).toBeInTheDocument();
    expect(screen.getByTestId('state-change-8')).toBeInTheDocument();
  });

  it('메타_저장시_PUT_frames_id_meta_호출', async () => {
    mock.onGet('/frames/100/meta').reply(200, {
      success: true,
      data: metaPayload,
      message: null,
      errorCode: null,
    });
    let putCalled = false;
    mock.onPut('/frames/100/meta').reply((config) => {
      putCalled = true;
      const body = JSON.parse(config.data);
      expect(body.envMeta.weather).toBe('RAIN');
      return [
        200,
        {
          success: true,
          data: { ...metaPayload, envMeta: { ...metaPayload.envMeta, weather: 'RAIN' } },
          message: null,
          errorCode: null,
        },
      ];
    });

    const user = userEvent.setup();
    renderWithProviders(<MetaReviewPage />, {
      initialEntries: ['/auto/42/meta?srcSn=100'],
      routes: [{ path: '/auto/:videoId/meta', element: <MetaReviewPage /> }],
    });

    await waitFor(() => {
      expect(screen.getByTestId('env-meta-form')).toBeInTheDocument();
    });

    // 날씨 변경 (CLEAR -> RAIN)
    const weatherSelect = screen.getByLabelText('날씨') as HTMLSelectElement;
    await user.selectOptions(weatherSelect, 'RAIN');

    // 저장 버튼 클릭
    await user.click(screen.getByRole('button', { name: '저장' }));

    await waitFor(() => {
      expect(putCalled).toBe(true);
    });
  });

  it('srcSn_누락시_프레임_선택_안내', () => {
    renderWithProviders(<MetaReviewPage />, {
      initialEntries: ['/auto/42/meta'],
      routes: [{ path: '/auto/:videoId/meta', element: <MetaReviewPage /> }],
    });
    expect(screen.getByText('프레임이 선택되지 않았습니다')).toBeInTheDocument();
  });
});
