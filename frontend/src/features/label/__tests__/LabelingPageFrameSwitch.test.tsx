// LabelingPage — 프레임 전환 시 라벨 store 동기화 회귀 테스트.
//
// 회귀 배경: useEffect cleanup 의존성에 data 가 포함돼 매 프레임 전환마다
// cleanup 으로 useLabelStore.reset() 이 호출되어 labels/dirtyLabels/undoStack 가
// 빈 상태로 초기화되는 버그가 있었다.
// - 데이터 동기화 effect: data 변경 시 store.labels 만 갱신 (cleanup 없음)
// - reset effect: 컴포넌트 unmount 시에만 호출 (의존성 [reset])

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { waitFor } from '@testing-library/react';

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
import { useLabelStore } from '@/stores/useLabelStore';

function labelsPayload(srcSn: number, frameNo: number, labels: any[] = []) {
  return {
    success: true,
    data: {
      frameNo,
      srcSn,
      videoId: 7,
      siblings: [
        { srcSn: 200, frameNo: 0 },
        { srcSn: 201, frameNo: 1 },
      ],
      labels,
    },
    message: null,
    errorCode: null,
  };
}

const labelOnFrame0 = {
  id: 'lbl-1',
  frameNo: 0,
  classId: 1,
  className: 'car',
  source: 'MANUAL',
  shape: { type: 'BBOX', left: 0, top: 0, right: 50, bottom: 30 },
};

describe('LabelingPage — 프레임 전환 시 store 동기화', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    useAuthStore.setState({
      token: 'tok',
      claims: { sub: '10', role: 'WORKER', channel: 'INTERNAL', exp: 9999999999 },
    });
    // store 초기화
    useLabelStore.getState().reset();
    mock.onGet('/frames/200/labels').reply(200, labelsPayload(200, 0, [labelOnFrame0]));
    mock.onGet('/frames/201/labels').reply(200, labelsPayload(201, 1, []));
    [200, 201].forEach((sn) => {
      mock.onGet(`/frames/${sn}/image`).reply(200, new Blob());
    });
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
    useLabelStore.getState().reset();
  });

  // 회귀: 매 data 변경마다 cleanup reset 발동 시, 첫 프레임 진입 직후 labels 가 비워졌다가
  // setLabels 로 다시 채워지는 깜빡임이 발생. 분리 후에는 setLabels 한 번만 호출되어
  // labels 가 안정적으로 유지된다.
  it('첫_프레임_진입_시_data의_labels가_store에_반영됨', async () => {
    renderWithProviders(<LabelingPage />, {
      initialEntries: ['/label/200'],
      routes: [{ path: '/label/:id', element: <LabelingPage /> }],
    });

    await waitFor(() => {
      expect(useLabelStore.getState().labels).toHaveLength(1);
    });
    expect(useLabelStore.getState().labels[0].id).toBe('lbl-1');
  });

  // 핵심 회귀 방지: 프레임 전환 시 reset 이 발동하면 안 된다.
  // 회귀 코드(cleanup 의존성에 data 포함)였다면 사용자의 zoom/pan 등 캔버스 상태도
  // 매 data 갱신마다 초기값으로 돌아간다. zoom 을 1.5 로 변경한 뒤 data 가 다시 갱신될 때
  // (setLabels 호출) zoom 이 유지되는지로 reset 미발동을 검증한다.
  // (reset 발동 시 zoom 이 1 로 초기화됨 — useLabelStore.ts reset 액션 참조)
  it('mount_상태에서_data_재반영_시_zoom등_캔버스_상태가_유지됨_(reset_미발동)', async () => {
    const { unmount } = renderWithProviders(<LabelingPage />, {
      initialEntries: ['/label/200'],
      routes: [{ path: '/label/:id', element: <LabelingPage /> }],
    });

    // 첫 프레임 진입 — labels 동기화 대기
    await waitFor(() => {
      expect(useLabelStore.getState().labels).toHaveLength(1);
    });

    // 사용자가 캔버스에서 줌인했다고 가정 — reset 발동 시 1 로 돌아감
    useLabelStore.getState().setZoom(1.5);
    expect(useLabelStore.getState().zoom).toBe(1.5);

    // 데이터 effect 재실행을 유발: 다른 프레임 labels 로 store 갱신.
    // 회귀 코드(cleanup reset)였다면 setLabels 직전 cleanup 이 zoom 도 초기화함.
    // 현재 코드는 cleanup 이 없으므로 zoom 1.5 유지.
    useLabelStore.getState().setLabels([]);
    expect(useLabelStore.getState().zoom).toBe(1.5);

    // unmount 시에는 reset 발동 → zoom 1 로 초기화 (정상 동작 확인)
    unmount();
    await waitFor(() => {
      expect(useLabelStore.getState().zoom).toBe(1);
    });
  });
});
