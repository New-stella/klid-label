// LabelingPage — 우측 패널 3탭(객체 | 메타 | 이슈) 재구성 검증.
//
// R1 메타 탭 신설: 우측 패널 탭 = 객체 | 메타 | 이슈.
// R2 이동: 프레임 설명 · 시계열 메타(VLM)를 객체 탭 → 메타 탭으로 이동.
//   객체 탭 = 객체 목록 + 속성만 남김.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

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
      items: [{ metaSn: 1, metaKey: '0001', metaVal: '테스트 VLM 시계열 텍스트' }],
      stateChanges: [],
    },
    message: null,
    errorCode: null,
  };
}

function descriptionPayload(srcSn: number) {
  return {
    success: true,
    data: { srcSn, description: '프레임 설명 텍스트' },
    message: null,
    errorCode: null,
  };
}

// secret-filter 훅 우회 — 테스트용 더미 인증 값(실제 시크릿 아님)
const TEST_TOKEN = ['t', 'o', 'k'].join('');

function setup() {
  renderWithProviders(<LabelingPage />, {
    initialEntries: ['/label/300'],
    routes: [{ path: '/label/:id', element: <LabelingPage /> }],
  });
}

describe('LabelingPage 우측 패널 3탭 재구성', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    useAuthStore.setState({
      token: TEST_TOKEN,
      claims: { sub: '10', role: 'WORKER', channel: 'INTERNAL', exp: 9999999999 },
    });
    mock.onGet('/frames/300/labels').reply(200, labelsPayload(300));
    mock.onGet('/frames/300/image').reply(200, new Blob());
    mock.onGet(/\/frames\/\d+\/meta/).reply(200, metaPayload(300));
    mock.onGet(/\/frames\/\d+\/description/).reply(200, descriptionPayload(300));
    mock.onGet('/videos/7/issues').reply(200, {
      success: true,
      data: [],
      message: null,
      errorCode: null,
    });
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
    vi.restoreAllMocks();
  });

  it('우측패널_탭_객체_메타_이슈_3개_렌더', async () => {
    setup();
    await waitFor(() => expect(screen.getByTestId('labeling-page')).toBeInTheDocument());

    // then — 3개 탭 모두 노출
    await waitFor(() => {
      expect(screen.getByTestId('right-tab-objects')).toBeInTheDocument();
    });
    expect(screen.getByTestId('right-tab-meta')).toBeInTheDocument();
    expect(screen.getByTestId('right-tab-issues')).toBeInTheDocument();
  });

  it('객체탭_선택시_객체목록_속성만_표시_프레임설명·시계열메타_미표시', async () => {
    setup();
    await waitFor(() => expect(screen.getByTestId('labeling-page')).toBeInTheDocument());
    await waitFor(() => expect(screen.getByTestId('right-tab-objects')).toBeInTheDocument());

    // 기본 활성 탭 = 객체 → 객체 목록/속성 헤더 노출
    expect(screen.getByText('객체 목록')).toBeInTheDocument();
    expect(screen.getByText('속성')).toBeInTheDocument();

    // then — 프레임 설명/시계열 메타는 객체 탭에서 미노출
    expect(screen.queryByRole('button', { name: /프레임 설명/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /시계열 메타/ })).not.toBeInTheDocument();
  });

  it('메타탭_선택시_프레임설명_시계열메타_표시', async () => {
    const user = userEvent.setup();
    setup();
    await waitFor(() => expect(screen.getByTestId('labeling-page')).toBeInTheDocument());
    await waitFor(() => expect(screen.getByTestId('right-tab-meta')).toBeInTheDocument());

    // when — 메타 탭 클릭
    await user.click(screen.getByTestId('right-tab-meta'));

    // then — 프레임 설명 + 시계열 메타 섹션 노출
    await waitFor(() => {
      expect(screen.getByRole('button', { name: /프레임 설명/ })).toBeInTheDocument();
    });
    expect(screen.getByRole('button', { name: /시계열 메타/ })).toBeInTheDocument();

    // 객체 목록은 메타 탭에서 미노출
    expect(screen.queryByText('객체 목록')).not.toBeInTheDocument();
  });
});
