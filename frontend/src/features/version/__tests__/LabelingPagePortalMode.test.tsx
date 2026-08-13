// 라벨링 화면의 버전 진입점 채널 분기 (R6/D4).
//
// 구 케이스는 **헤더 [히스토리] 버튼**을 봤다. 그 버튼은 폐지됐고 진입점은 캔버스 상단 옵션바의
// [버전] 버튼 + 진입 시 「시작 버전 선택」 모달로 옮겨졌다. 검증 축("포털에는 버전 진입이 없다")은
// 그대로이고 대상 요소만 바뀐다 — 포털은 버전관리 미제공(ADR-013)이다.
//
// react-konva는 jsdom에서 실제 렌더링 안 됨 → 모킹으로 헤더 영역만 검증.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import MockAdapter from 'axios-mock-adapter';

vi.mock('react-konva', async () => (await import('@/test/konvaMock')).createKonvaMock());

import { apiClient } from '@/lib/api/client';
import { LabelingPage } from '@/pages/label/LabelingPage';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';

function setChannel(channel: 'INTERNAL' | 'PORTAL', role: 'REVIEWER' | 'WORKER' | 'PORTAL_USER') {
  useAuthStore.setState({
    token: 'tok',
    claims: { sub: 'u-7', role, channel, exp: 9999999999 },
  });
}

const labelsPayload = {
  success: true,
  data: {
    frameNo: 1,
    srcSn: 123,
    labels: [],
  },
  message: null,
  errorCode: null,
};

describe('LabelingPage 버전 진입점 portalMode 분기', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  it('INTERNAL_채널에서_버전_진입점_렌더', async () => {
    setChannel('INTERNAL', 'WORKER');
    mock.onGet('/frames/123/labels').reply(200, labelsPayload);

    renderWithProviders(<LabelingPage />, {
      initialEntries: ['/label/123'],
      routes: [{ path: '/label/:id', element: <LabelingPage /> }],
    });

    await waitFor(() => {
      expect(screen.getByTestId('start-version-open')).toBeInTheDocument();
    });
    // 헤더에는 되돌아오지 않는다 — 진입점이 둘로 갈리면 어느 쪽이 최신인지 알 수 없다.
    expect(screen.queryByTestId('history-toggle')).toBeNull();
  });

  it('portalMode에서_버전_진입점_미렌더', async () => {
    setChannel('PORTAL', 'PORTAL_USER');
    mock.onGet('/frames/123/labels').reply(200, labelsPayload);

    renderWithProviders(<LabelingPage />, {
      initialEntries: ['/label/123'],
      routes: [{ path: '/label/:id', element: <LabelingPage /> }],
    });

    // 라벨이 로드된 뒤에도 버전 진입점은 노출되지 않아야 함
    await waitFor(() => {
      expect(screen.getByTestId('labeling-page')).toBeInTheDocument();
    });

    expect(screen.queryByTestId('start-version-open')).toBeNull();
    expect(screen.queryByTestId('start-version-modal')).toBeNull();
  });
});
