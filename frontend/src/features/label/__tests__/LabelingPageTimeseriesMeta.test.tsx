// LabelingPage — 우측 사이드패널 시계열 메타 렌더링 검증.
// "시계열 메타" 텍스트 존재 확인.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createElement, type ReactNode } from 'react';

vi.mock('react-konva', () => {
  const passthrough = (name: string) => {
    const KonvaMock = ({
      children,
      ...rest
    }: {
      children?: ReactNode;
      [key: string]: unknown;
    }) => createElement('div', { 'data-konva': name, ...rest }, children);
    KonvaMock.displayName = `KonvaMock(${name})`;
    return KonvaMock;
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

function labelsPayload(srcSn: number) {
  return {
    success: true,
    data: {
      frameNo: 0,
      srcSn,
      videoId: 7,
      siblings: [{ srcSn, frameNo: 0 }],
      labels: [],
    },
    message: null,
    errorCode: null,
  };
}

function metaPayload(srcSn: number) {
  return {
    success: true,
    data: {
      srcSn,
      frameNo: 0,
      imageUrl: '',
      imageWidth: 0,
      imageHeight: 0,
      vlmText: '테스트 VLM 시계열 텍스트',
      stateChanges: [],
    },
    message: null,
    errorCode: null,
  };
}

describe('LabelingPage 시계열 메타 사이드패널', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    useAuthStore.setState({
      token: 'dummy-tok',
      claims: { sub: '10', role: 'WORKER', channel: 'INTERNAL', exp: 9999999999 },
    });
    mock.onGet('/frames/300/labels').reply(200, labelsPayload(300));
    mock.onGet('/frames/300/image').reply(200, new Blob());
    // meta API (useMeta 호출 대상)
    mock.onGet(/\/frames\/\d+\/meta/).reply(200, metaPayload(300));
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  it('LabelingPage_메타탭에_시계열_메타_렌더링', async () => {
    // given
    const user = userEvent.setup();
    renderWithProviders(<LabelingPage />, {
      initialEntries: ['/label/300'],
      routes: [{ path: '/label/:id', element: <LabelingPage /> }],
    });

    // when — 라벨링 페이지 로딩 완료 후 '메타' 탭으로 전환
    await waitFor(() => expect(screen.getByTestId('labeling-page')).toBeInTheDocument());
    await waitFor(() => expect(screen.getByTestId('right-tab-meta')).toBeInTheDocument());
    await user.click(screen.getByTestId('right-tab-meta'));

    // then — 메타 탭에 "시계열 메타" 토글 버튼이 존재
    await waitFor(() => {
      expect(screen.getByRole('button', { name: /시계열 메타/ })).toBeInTheDocument();
    });
  });
});
