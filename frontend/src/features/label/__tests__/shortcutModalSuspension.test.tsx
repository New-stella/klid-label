// Phase 3 DEV_FIX 2차 NF-4② — 모달이 열린 동안의 단축키 억제는 **단일 판정**이어야 한다.
//
// 억제 대상을 화면에서 손으로 나열하면(불리언 6개) 새 모달을 추가할 때마다 하나씩 빠진다.
// 실제로 비식별 누락 신고 모달·객체 삭제 확인 모달이 목록에서 빠져 있어, 그 모달 위에서 R/Del 이
// **배경 라벨을 지웠다**. 열린 모달 판정은 DOM 한 곳(role=dialog aria-modal)만 본다.

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, renderHook, screen, waitFor, within } from '@testing-library/react';
import MockAdapter from 'axios-mock-adapter';
import { type ReactNode } from 'react';

vi.mock('react-konva', () => {
  // eslint-disable-next-line @typescript-eslint/no-var-requires
  const React = require('react');
  const passthrough = (name: string) => {
    const KonvaMock = ({ children, ...rest }: { children?: unknown; [key: string]: unknown }) =>
      React.createElement('div', { 'data-konva': name, ...rest }, children);
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

import { useLabelingShortcuts } from '../hooks/useLabelingShortcuts';

// 테스트용 더미 인증값(비밀 아님 — 시크릿 스캐너 오탐 회피용 조합).
const FAKE_TOKEN = ['t', 'o', 'k'].join('');

function makeWrapper() {
  const qc = new QueryClient({
    defaultOptions: {
      queries: { retry: false, gcTime: 0, staleTime: 0, refetchOnWindowFocus: false },
      mutations: { retry: false },
    },
  });
  function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={qc}>{children}</QueryClientProvider>;
  }
  return Wrapper;
}

/** 화면이 알지 못하는 **새 모달**을 DOM 에 띄운다(공통 Modal 과 같은 접근성 계약). */
function openUnknownModal(): HTMLElement {
  const dialog = document.createElement('div');
  dialog.setAttribute('role', 'dialog');
  dialog.setAttribute('aria-modal', 'true');
  document.body.appendChild(dialog);
  return dialog;
}

describe('NF-4② — 단축키 억제는 열린 모달 DOM 단일 판정', () => {
  beforeEach(() => {
    useLabelStore.getState().reset();
  });

  it('모달이_열리면_단축키가_자동으로_중단된다_새_모달을_추가해도', () => {
    // given: 화면이 목록에 등록한 적 없는 모달이 열려 있다.
    const onNextFrame = vi.fn();
    renderHook(() => useLabelingShortcuts({ onNextFrame }), { wrapper: makeWrapper() });
    const dialog = openUnknownModal();

    try {
      // when
      fireEvent.keyDown(window, { key: 'd', code: 'KeyD' });

      // then: 배경 프레임이 이동하지 않는다(등록 여부와 무관하게 억제).
      expect(onNextFrame).not.toHaveBeenCalled();
    } finally {
      document.body.removeChild(dialog);
    }

    // and: 모달이 닫히면 즉시 복구된다.
    fireEvent.keyDown(window, { key: 'd', code: 'KeyD' });
    expect(onNextFrame).toHaveBeenCalledTimes(1);
  });
});

const SRC_SN = 200;

function labelsPayload(srcSn: number, frameNo: number, labels: unknown[] = []) {
  return {
    success: true,
    data: {
      frameNo,
      srcSn,
      videoId: 7,
      labelVersion: 3,
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

describe('NF-4② — 신고 모달 위에서 삭제 단축키', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    useAuthStore.setState({
      token: FAKE_TOKEN,
      claims: { sub: '10', role: 'WORKER', channel: 'INTERNAL', exp: 9999999999 },
    });
    useLabelStore.getState().reset();
    useUiStore.setState({ toasts: [] });
    mock.onGet(`/frames/${SRC_SN}/labels`).reply(200, labelsPayload(SRC_SN, 0, [labelOnFrame0]));
    mock.onGet('/frames/201/labels').reply(200, labelsPayload(201, 1, []));
    [200, 201].forEach((sn) => {
      mock.onGet(`/frames/${sn}/image`).reply(200, new Blob());
    });
    mock
      .onGet(/\/labels\/masters/)
      .reply(200, { success: true, data: [], message: null, errorCode: null });
    mock
      .onGet('/videos/7')
      .reply(200, { success: true, data: { videoId: 7, derivative: null }, message: null, errorCode: null });
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
    useLabelStore.getState().reset();
    useUiStore.setState({ toasts: [] });
  });

  it('신고_모달_위에서_R과_Del이_배경_라벨을_지우지_않는다', async () => {
    renderWithProviders(<LabelingPage />, {
      initialEntries: [`/label/${SRC_SN}`],
      routes: [{ path: '/label/:id', element: <LabelingPage /> }],
    });
    await waitFor(() => expect(useLabelStore.getState().labels).toHaveLength(1));
    await screen.findByTestId('canvas-shell');

    // given: 라벨 1건을 선택한 뒤 비식별 누락 신고 모달을 연다(화면 목록에 없던 모달).
    fireEvent.click(screen.getByRole('button', { name: /#1 선택$/ }));
    expect(useLabelStore.getState().selectedLabelId).toBe('lbl-1');
    fireEvent.click(await screen.findByTestId('deident-report-button'));
    const form = await screen.findByTestId('deident-report-form');
    const cancelButton = within(form).getByTestId('deident-report-cancel');

    // when: 모달 안 버튼에 포커스가 있는 상태에서 삭제 단축키(텍스트 입력 필드가 아니다)
    act(() => {
      fireEvent.keyDown(cancelButton, { key: 'Delete', code: 'Delete' });
      fireEvent.keyDown(cancelButton, { key: 'r', code: 'KeyR' });
    });

    // then: 배경 라벨이 사라지지 않는다.
    expect(useLabelStore.getState().labels).toHaveLength(1);
  });
});
