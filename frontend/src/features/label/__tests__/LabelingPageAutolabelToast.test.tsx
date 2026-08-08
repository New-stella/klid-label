// Phase 13b — LabelingPage YOLO 오토라벨 토스트 재배선(mock 신호 = BE ApiResponse.message).
// message 있으면 경고 토스트(자동적용 차단), 없으면 성공 토스트("N건 적용됨").
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
    Transformer: passthrough('Transformer'),
  };
});

import { apiClient } from '@/lib/api/client';
import { LabelingPage } from '@/pages/label/LabelingPage';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';
import { useLabelStore } from '@/stores/useLabelStore';
import { useUiStore } from '@/stores/useUiStore';

function labelsPayload() {
  return {
    success: true,
    data: {
      frameNo: 0,
      srcSn: 300,
      videoId: 7,
      siblings: [{ srcSn: 300, frameNo: 0, hasLabel: false }],
      labels: [],
    },
    message: null,
    errorCode: null,
  };
}

const TEST_TOKEN = ['t', 'o', 'k'].join('');

function setup() {
  renderWithProviders(<LabelingPage />, {
    initialEntries: ['/label/300'],
    routes: [{ path: '/label/:id', element: <LabelingPage /> }],
  });
}

describe('LabelingPage — YOLO 오토라벨 토스트(mock message 재배선)', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    useLabelStore.getState().reset();
    useUiStore.setState({ toasts: [] });
    mock = new MockAdapter(apiClient);
    useAuthStore.setState({
      token: TEST_TOKEN,
      claims: { sub: '10', role: 'WORKER', channel: 'INTERNAL', exp: 9999999999 },
    });
    mock.onGet('/frames/300/labels').reply(200, labelsPayload());
    mock.onGet(/\/frames\/\d+\/image/).reply(200, new Blob());
    mock.onGet(/\/frames\/\d+\/meta/).reply(200, { success: true, data: null, message: null, errorCode: null });
    mock.onGet(/\/frames\/\d+\/description/).reply(200, {
      success: true,
      data: { srcSn: 300, description: '' },
      message: null,
      errorCode: null,
    });
    mock.onGet('/manage/labels').reply(200, { success: true, data: [], message: null, errorCode: null });
    // AI 탐지 팝업 후보 — 매핑된 라벨이 하나 이상 있어야 [일반] 버튼이 활성화된다.
    mock.onGet('/manage/labels/detect-candidates').reply(200, {
      success: true,
      data: [
        { labelId: 10, name: '사람', color: '#EF4444', type: 'BBOX', dtctTypeCd: 'person' },
      ],
      message: null,
      errorCode: null,
    });
    mock.onGet('/videos/7/issues').reply(200, { success: true, data: [], message: null, errorCode: null });
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
    useLabelStore.getState().reset();
    useUiStore.setState({ toasts: [] });
    vi.restoreAllMocks();
  });

  it('message있으면_mock경고_토스트로_자동적용_차단', async () => {
    mock.onPost('/frames/300/autolabel').reply(200, {
      success: true,
      data: { srcSn: 300, savedCount: 0, labels: [] },
      message: 'AI 모델 미로드 — 결과 신뢰 불가',
      errorCode: null,
    });

    setup();
    const btn = await screen.findByRole('button', { name: 'AI 탐지' });
    await userEvent.click(btn);
    // Phase 4 — 버튼 클릭은 AI Tool 팝업을 연다. 미선택(전체) 상태로 [일반] 실행.
    await userEvent.click(await screen.findByRole('button', { name: '일반' }));

    await waitFor(() => {
      const toasts = useUiStore.getState().toasts;
      expect(toasts.some((t) => t.variant === 'warning' && t.message === 'AI 모델 미로드 — 결과 신뢰 불가')).toBe(true);
    });
    // mock 경고 시 성공 토스트는 뜨지 않는다.
    expect(useUiStore.getState().toasts.some((t) => t.variant === 'success')).toBe(false);
  });

  it('savedCount_양수이면_성공_토스트', async () => {
    mock.onPost('/frames/300/autolabel').reply(200, {
      success: true,
      data: {
        srcSn: 300,
        savedCount: 2,
        labels: [
          { lblSn: 1, labelId: 10, label: 'person', points: [1, 2, 3, 4], score: 0.9, trackId: 3 },
          { lblSn: 2, labelId: null, label: 'car', points: [5, 6, 7, 8], score: 0.8, trackId: null },
        ],
      },
      message: null,
      errorCode: null,
    });

    setup();
    const btn = await screen.findByRole('button', { name: 'AI 탐지' });
    await userEvent.click(btn);
    // Phase 4 — AI Tool 팝업에서 미선택(전체)으로 [일반] 실행.
    await userEvent.click(await screen.findByRole('button', { name: '일반' }));

    await waitFor(() => {
      const toasts = useUiStore.getState().toasts;
      expect(toasts.some((t) => t.variant === 'success' && t.message === 'AI 탐지 2건 적용됨')).toBe(true);
    });
    expect(useUiStore.getState().toasts.some((t) => t.variant === 'warning')).toBe(false);
  });
});
