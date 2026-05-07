// 포털 채널 라벨링 화면 — 미노출 보장 회귀 테스트.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import MockAdapter from 'axios-mock-adapter';

vi.mock('react-konva', () => {
  // eslint-disable-next-line @typescript-eslint/no-var-requires
  const React = require('react');
  const passthrough = (name: string) => {
    // eslint-disable-next-line react/display-name, @typescript-eslint/no-explicit-any
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

const labelsPayload = {
  success: true,
  data: {
    frameNo: 1,
    srcSn: 555,
    labels: [],
  },
  message: null,
  errorCode: null,
};

describe('포털 채널 라벨링 미노출', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    useAuthStore.setState({
      token: 'tok',
      claims: { sub: 'u-99', role: 'PORTAL_USER', channel: 'PORTAL', exp: 9999999999 },
    });
    mock.onGet('/frames/555/labels').reply(200, labelsPayload);
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  function renderPortalLabel() {
    return renderWithProviders(<LabelingPage />, {
      initialEntries: ['/label/555'],
      routes: [{ path: '/label/:id', element: <LabelingPage /> }],
    });
  }

  it('포털_라벨링_화면_검수제출_버튼_미렌더', async () => {
    renderPortalLabel();
    await waitFor(() => expect(screen.getByTestId('labeling-page')).toBeInTheDocument());
    expect(screen.queryByTestId('submit-review-button')).toBeNull();
    expect(screen.queryByRole('button', { name: '검수제출' })).toBeNull();
  });

  it('포털_라벨링_화면_VLM_메타_탭_미노출', async () => {
    renderPortalLabel();
    await waitFor(() => expect(screen.getByTestId('labeling-page')).toBeInTheDocument());
    // VLM 객체 검증, 시계열 메타 탭은 외부 시스템 책임 — 포털 라벨링 UI에 노출되면 안됨.
    expect(screen.queryByText(/VLM/)).toBeNull();
    expect(screen.queryByText(/시계열 메타/)).toBeNull();
    expect(screen.queryByRole('tab', { name: /메타/ })).toBeNull();
  });
});
