// Phase 4 — LabelingPage AI 탐지(일반) 병합 재배선.
//  - 검출 결과는 BE 미저장 → PUT 없이 작업본에 병합(기존 라벨 보존 + 중복 스킵).
//  - 병합 직후 같은 프레임 refetch 가 와도 미저장 편집(dirty)을 덮지 않는다.

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

vi.mock('react-konva', () => {
  const React = require('react');
  const passthrough = (name: string) => {
    return ({ children, ...rest }: any) =>
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
    Transformer: passthrough('Transformer'),
  };
});

import { apiClient } from '@/lib/api/client';
import { LabelingPage } from '@/pages/label/LabelingPage';
import { LABEL_KEYS } from '@/lib/queryKeys';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';
import { useLabelStore } from '@/stores/useLabelStore';
import { useUiStore } from '@/stores/useUiStore';

const EXISTING = {
  id: 91,
  frameNo: 0,
  lblTypeCd: 'BBOX',
  label: 'car',
  labelId: 5,
  points: [[0, 0], [10, 10]],
  autoLblYn: 'N',
  confScore: null,
  trackId: null,
};

function labelsPayload() {
  return {
    success: true,
    data: {
      frameNo: 0,
      srcSn: 300,
      videoId: 7,
      siblings: [{ srcSn: 300, frameNo: 0, hasLabel: true }],
      labels: [EXISTING],
    },
    message: null,
    errorCode: null,
  };
}

const TEST_TOKEN = ['t', 'o', 'k'].join('');

describe('LabelingPage — AI 탐지 병합(미저장·PUT 미호출·dirty 보존)', () => {
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
    mock.onGet('/videos/7/issues').reply(200, { success: true, data: [], message: null, errorCode: null });
    mock.onGet(/\/reviews\/\d+/).reply(200, { success: true, data: null, message: null, errorCode: null });
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
    useLabelStore.getState().reset();
    useUiStore.setState({ toasts: [] });
    vi.restoreAllMocks();
  });

  function setupWithClient() {
    return renderWithProviders(<LabelingPage />, {
      initialEntries: ['/label/300'],
      routes: [{ path: '/label/:id', element: <LabelingPage /> }],
    });
  }

  async function runDetect() {
    const btn = await screen.findByRole('button', { name: 'AI 탐지' });
    await userEvent.click(btn);
    await userEvent.click(await screen.findByRole('button', { name: '일반' }));
  }

  it('오토라벨_직후_PUT저장이_호출되지_않는다_그리고_기존라벨_유지한채_병합', async () => {
    const putSpy = vi.fn();
    mock.onPut('/frames/300/labels').reply(() => {
      putSpy();
      return [200, { success: true, data: labelsPayload().data, message: null, errorCode: null }];
    });
    mock.onPost('/frames/300/autolabel').reply(200, {
      success: true,
      data: {
        srcSn: 300,
        savedCount: 1,
        labels: [{ lblSn: null, labelId: 10, label: 'person', points: [500, 500, 600, 600], score: 0.9, trackId: null }],
      },
      message: null,
      errorCode: null,
    });

    setupWithClient();
    // 기존 라벨(car)이 store 에 로드될 때까지 대기.
    await waitFor(() => expect(useLabelStore.getState().labels).toHaveLength(1));

    await runDetect();

    // 검출 결과(person)가 기존(car)을 유지한 채 병합 → 2건.
    await waitFor(() => expect(useLabelStore.getState().labels).toHaveLength(2));
    const labels = useLabelStore.getState().labels;
    expect(labels.some((l) => l.className === 'car')).toBe(true);
    expect(labels.some((l) => l.className === 'person')).toBe(true);
    // 병합은 dirty 로 표시.
    expect(useLabelStore.getState().dirtyLabels.size).toBe(1);
    // PUT(자동저장) 미호출.
    expect(putSpy).not.toHaveBeenCalled();
  });

  it('refetch가_와도_dirty편집이_유실되지_않는다', async () => {
    mock.onPost('/frames/300/autolabel').reply(200, {
      success: true,
      data: {
        srcSn: 300,
        savedCount: 1,
        labels: [{ lblSn: null, labelId: 10, label: 'person', points: [500, 500, 600, 600], score: 0.9, trackId: null }],
      },
      message: null,
      errorCode: null,
    });

    const { queryClient } = setupWithClient();
    await waitFor(() => expect(useLabelStore.getState().labels).toHaveLength(1));
    await runDetect();
    await waitFor(() => expect(useLabelStore.getState().labels).toHaveLength(2));

    // 같은 프레임 refetch 유발 — 서버 응답은 여전히 기존 1건(car)만.
    await queryClient.invalidateQueries({ queryKey: LABEL_KEYS.all });

    // dirty 편집(병합된 person)이 refetch 로 유실되지 않아야 한다.
    await new Promise((r) => setTimeout(r, 50));
    expect(useLabelStore.getState().labels).toHaveLength(2);
    expect(useLabelStore.getState().labels.some((l) => l.className === 'person')).toBe(true);
  });
});
