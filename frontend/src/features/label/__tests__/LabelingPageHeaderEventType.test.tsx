// UI-055 회귀 가드 — 라벨링 헤더의 이벤트 유형 배지 배선.
//
// 결함: `LabelingPage` 가 `LabelHeader` 에 `eventType={undefined}` 를 **항상 고정 전달**해
//       배지가 영영 뜨지 않았다(컴포넌트는 멀쩡한데 값이 안 넘어가 기능이 죽어 있던 유형).
//       값 출처는 영상 상세(`GET /videos/{rawSn}`)의 eventName(한글) → eventTypeCd(EV-코드) 순.
//
// 함께 지키는 것: 헤더의 `[폐기] N개 객체` 표시가 되살아나지 않는가(B4#6).

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor } from '@testing-library/react';

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

const SRC_SN = 410;
const RAW_SN = 41;

function labelsPayload() {
  return {
    success: true,
    data: {
      frameNo: 0,
      srcSn: SRC_SN,
      videoId: RAW_SN,
      siblings: [{ srcSn: SRC_SN, frameNo: 0 }],
      labels: [],
    },
    message: null,
    errorCode: null,
  };
}

function videoDetailPayload(extra: Record<string, unknown>) {
  return {
    success: true,
    data: {
      id: RAW_SN,
      cctvName: '테스트 CCTV',
      vmsClipId: 'clip-1',
      frameCount: 1,
      status: 'COMPLETED',
      capturedAt: '2026-01-01T00:00:00',
      duration: 10,
      fileSizeMb: 1,
      resolution: '1920x1080',
      framePreviews: [],
      ...extra,
    },
    message: null,
    errorCode: null,
  };
}

function renderPage() {
  return renderWithProviders(<LabelingPage />, {
    initialEntries: [`/label/${SRC_SN}`],
    routes: [{ path: '/label/:id', element: <LabelingPage /> }],
  });
}

describe('LabelingPage 헤더 이벤트 유형 배지 (UI-055)', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    useAuthStore.setState({
      token: 'tok',
      claims: { sub: '10', role: 'WORKER', channel: 'INTERNAL', exp: 9999999999 },
    });
    mock.onGet(`/frames/${SRC_SN}/image`).reply(200, new Blob());
    mock.onGet(`/frames/${SRC_SN}/labels`).reply(200, labelsPayload());
    mock.onGet(`/videos/${RAW_SN}/issues`).reply(200, {
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

  it('영상의_이벤트명이_헤더_배지로_렌더된다', async () => {
    // given: 영상 상세가 한글 이벤트명을 내려준다
    mock
      .onGet(`/videos/${RAW_SN}`)
      .reply(200, videoDetailPayload({ eventName: '화재', eventTypeCd: 'EV01000101' }));

    // when
    renderPage();

    // then: 구 동작(eventType 고정 undefined)에서는 절대 나타나지 않던 배지가 뜬다
    await waitFor(() => {
      expect(screen.getByText('화재')).toBeInTheDocument();
    });
  });

  it('이벤트명이_없으면_EV코드로_폴백한다', async () => {
    // given: 관제가 이름을 안 보낸 영상(eventName 부재)
    mock
      .onGet(`/videos/${RAW_SN}`)
      .reply(200, videoDetailPayload({ eventTypeCd: 'EV01000101' }));

    // when
    renderPage();

    // then: 코드가 배지로 전달되어 라벨 맵 해석을 탄다(값 자체가 안 넘어가지 않는다)
    await waitFor(() => {
      expect(screen.getByTestId('labeling-page')).toBeInTheDocument();
    });
    await screen.findByRole('option', { name: '프레임 0' });
    // 라벨 맵이 비어 있으면 코드 원문이 그대로 표시된다.
    await waitFor(() => {
      expect(screen.getByText('EV01000101')).toBeInTheDocument();
    });
  });

  it('헤더에_객체_수_표시가_되살아나지_않는다', async () => {
    // given
    mock.onGet(`/videos/${RAW_SN}`).reply(200, videoDetailPayload({ eventName: '화재' }));

    // when
    renderPage();
    await screen.findByRole('option', { name: '프레임 0' });

    // then: 표시 지점은 우측 객체 탭 하나뿐이다(헤더 중복 표시 금지).
    expect(screen.getAllByLabelText('객체 수')).toHaveLength(1);
    expect(screen.getByTestId('object-count-badge')).toBeInTheDocument();
  });
});
