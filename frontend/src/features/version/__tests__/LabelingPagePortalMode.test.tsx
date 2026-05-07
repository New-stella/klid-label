// react-konva는 jsdom에서 실제 렌더링 안 됨 → 모킹으로 헤더 영역만 검증.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import MockAdapter from 'axios-mock-adapter';

vi.mock('react-konva', () => {
  // eslint-disable-next-line @typescript-eslint/no-var-requires
  const React = require('react');
  const passthrough = (name: string) => {
    return ({ children, ...rest }: any) =>
      // eslint-disable-next-line react/no-children-prop
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

describe('LabelingPage 히스토리 버튼 portalMode 분기', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  it('INTERNAL_채널에서_히스토리_버튼_렌더', async () => {
    setChannel('INTERNAL', 'WORKER');
    mock.onGet('/frames/123/labels').reply(200, labelsPayload);

    renderWithProviders(<LabelingPage />, {
      initialEntries: ['/label/123'],
      routes: [{ path: '/label/:id', element: <LabelingPage /> }],
    });

    await waitFor(() => {
      expect(screen.getByText('히스토리')).toBeInTheDocument();
    });
  });

  it('portalMode에서_라벨링_헤더_히스토리_버튼_미렌더', async () => {
    setChannel('PORTAL', 'PORTAL_USER');
    mock.onGet('/frames/123/labels').reply(200, labelsPayload);

    renderWithProviders(<LabelingPage />, {
      initialEntries: ['/label/123'],
      routes: [{ path: '/label/:id', element: <LabelingPage /> }],
    });

    // 라벨이 로드된 뒤에도 히스토리 링크는 노출되지 않아야 함
    await waitFor(() => {
      expect(screen.getByTestId('labeling-page')).toBeInTheDocument();
    });

    expect(screen.queryByText('히스토리')).not.toBeInTheDocument();
  });
});
