// Phase A — 라벨 저장 중복 제출 가드 (Ctrl+S 연타 차단).
// react-konva는 jsdom에서 실제 렌더링 안 됨 → 모킹.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { fireEvent, screen, waitFor } from '@testing-library/react';
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

function labelsPayload(videoId: number) {
  return {
    success: true,
    data: { frameNo: 0, srcSn: 100, videoId, labels: [], siblings: [] },
    message: null,
    errorCode: null,
  };
}

function reviewPayload(videoId: number, status: string) {
  return {
    success: true,
    data: {
      id: 1,
      videoId,
      cctvName: 'CCTV-1',
      workerId: 7,
      workerName: '홍길동',
      submittedAt: '2026-05-07T10:00:00Z',
      labelCount: 0,
      status,
    },
    message: null,
    errorCode: null,
  };
}

describe('LabelingPage 저장 중복 제출 가드 (Phase A)', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    useAuthStore.setState({
      token: 'dummy-token',
      claims: { sub: 'u', role: 'WORKER', channel: 'INTERNAL', exp: 9999999999 },
    });
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  it('라벨_저장_pending_중_Ctrl_S_재호출시_추가_PUT_미발생', async () => {
    // given: ASSIGNED 상태(저장 가능) + 라벨 PUT 은 응답 지연으로 pending 고정.
    mock.onGet('/frames/100/labels').reply(200, labelsPayload(18));
    mock.onGet('/reviews/18').reply(200, reviewPayload(18, 'ASSIGNED'));
    let putCount = 0;
    mock.onPut('/frames/100/labels').reply(() => {
      putCount += 1;
      // 응답 미확정 → useUpdateLabels.isPending(saving) 이 계속 true 로 유지.
      return new Promise(() => {});
    });

    renderWithProviders(<LabelingPage />, {
      initialEntries: ['/label/100'],
      routes: [{ path: '/label/:id', element: <LabelingPage /> }],
    });

    // 프레임 로드 완료(저장 대상 currentFrame 확정) 대기.
    await screen.findByTestId('submit-review-button');

    // when: 첫 Ctrl+S → 라벨 PUT 발화 후 pending 유지.
    fireEvent.keyDown(window, { code: 'KeyS', key: 's', ctrlKey: true });
    await waitFor(() => expect(putCount).toBe(1));

    // when: pending 중 Ctrl+S 재입력 (연타 시뮬레이션).
    fireEvent.keyDown(window, { code: 'KeyS', key: 's', ctrlKey: true });
    await new Promise((r) => setTimeout(r, 50));

    // then: 중복 저장 가드로 PUT 은 여전히 1회.
    expect(putCount).toBe(1);
  });
});
