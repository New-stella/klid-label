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
    // 시계열 메타 패널이 데이터를 받으면 비동기로 렌더되는 경로까지 활성화 —
    // 포털 모드 가드가 없으면 패널이 결국 노출되므로, mock 을 제공해 회귀를 확실히 잡는다.
    mock.onGet(/\/frames\/\d+\/meta/).reply(200, {
      success: true,
      data: {
        srcSn: 555,
        frameNo: 1,
        imageUrl: '',
        imageWidth: 0,
        imageHeight: 0,
        vlmText: '포털에 노출되면 안 되는 VLM 시계열 텍스트',
        stateChanges: [],
      },
      message: null,
      errorCode: null,
    });
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
    // useMeta 응답이 비동기로 들어와 시계열 메타 패널이 뒤늦게 렌더될 수 있으므로,
    // 메타 쿼리/렌더가 완료될 시간을 충분히 준 뒤에도 끝까지 미노출임을 보장한다.
    // (가드가 없으면 이 대기 후 "시계열 메타" 버튼이 나타나 RED 가 된다.)
    await new Promise((resolve) => setTimeout(resolve, 300));
    // VLM 객체 검증, 시계열 메타 탭은 외부 시스템 책임 — 포털 라벨링 UI에 노출되면 안됨.
    expect(screen.queryByRole('button', { name: /시계열 메타/ })).toBeNull();
    expect(screen.queryByText(/VLM/)).toBeNull();
    expect(screen.queryByText(/시계열 메타/)).toBeNull();
    expect(screen.queryByRole('tab', { name: /메타/ })).toBeNull();
  });
});
