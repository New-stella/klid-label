// LabelingPage — 저장 이벤트 되돌리기 확인모달 → 작업본 역적용 플로우.
//
// 히스토리 패널 "변경 이력" 탭의 저장 이벤트 카드 [되돌리기] → 확인모달 → 승인 시
// 스토어 작업본에 역적용(UPDATED before 복원) + dirty 표시(저장으로 확정).

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

vi.mock('react-konva', () => {
  // eslint-disable-next-line @typescript-eslint/no-var-requires
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
  };
});

import { apiClient } from '@/lib/api/client';
import { LabelingPage } from '@/pages/label/LabelingPage';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';
import { useLabelStore } from '@/stores/useLabelStore';
import { useUiStore } from '@/stores/useUiStore';

function labelsPayload(srcSn: number, opts?: { lockSttsCd?: string }) {
  return {
    success: true,
    data: {
      frameNo: 0,
      srcSn,
      videoId: 7,
      siblings: [{ srcSn, frameNo: 0 }],
      // 잠금 게이팅 검증용 — LOCKED_FOR_REDEIDENT 이면 되돌리기 차단.
      ...(opts?.lockSttsCd ? { lockSttsCd: opts.lockSttsCd } : {}),
      labels: [
        {
          id: 100,
          frameNo: 0,
          lblTypeCd: 'BBOX',
          label: 'person',
          labelId: 3,
          points: [[5, 5], [20, 20]],
          autoLblYn: 'N',
        },
      ],
    },
    message: null,
    errorCode: null,
  };
}

function historyPayload(lblSn = 100) {
  return {
    success: true,
    data: {
      content: [
        {
          lblHstrySn: 1,
          srcSn: 300,
          regDt: '2026-07-21T00:00:00Z',
          actor: 'worker-1',
          addCnt: 0,
          mdfcnCnt: 1,
          delCnt: 0,
          changes: [
            {
              lblSn,
              changeKind: 'UPDATED',
              labelName: 'person',
              before: { lblTypeCd: 'BBOX', labelId: 3, labelNm: 'person', pointCn: '[[0,0],[10,10]]' },
              after: { lblTypeCd: 'BBOX', labelId: 3, labelNm: 'person', pointCn: '[[5,5],[20,20]]' },
            },
          ],
        },
      ],
      number: 0,
      size: 20,
      totalElements: 1,
      totalPages: 1,
    },
    message: null,
    errorCode: null,
  };
}

describe('LabelingPage 저장 이벤트 되돌리기', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    useLabelStore.getState().reset();
    useUiStore.setState({ toasts: [] });
    useAuthStore.setState({
      token: 'dummy-token',
      claims: { sub: '10', role: 'WORKER', channel: 'INTERNAL', exp: 9999999999 },
    });
    mock.onGet('/frames/300/labels').reply(200, labelsPayload(300));
    mock.onGet('/frames/300/image').reply(200, new Blob());
    mock.onGet('/frames/300/versions').reply(200, {
      success: true,
      data: [],
      message: null,
      errorCode: null,
    });
    mock.onGet(/\/frames\/\d+\/label-history/).reply(200, historyPayload());
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
    useLabelStore.getState().reset();
    useUiStore.setState({ toasts: [] });
  });

  it('되돌리기_확인모달_승인시_작업본에_역적용되고_dirty표시', async () => {
    const user = userEvent.setup();
    renderWithProviders(<LabelingPage />, {
      initialEntries: ['/label/300'],
      routes: [{ path: '/label/:id', element: <LabelingPage /> }],
    });

    await waitFor(() => expect(screen.getByTestId('labeling-page')).toBeInTheDocument());
    // 작업본 로드 확인 — 라벨 serverId=100 이 현재 좌표(5,5,20,20).
    await waitFor(() =>
      expect(useLabelStore.getState().labels.find((l) => l.serverId === 100)?.shape).toEqual({
        type: 'BBOX',
        left: 5,
        top: 5,
        right: 20,
        bottom: 20,
      }),
    );

    // 히스토리 패널 열기 (기본 탭 = 변경 이력).
    await user.click(await screen.findByTestId('history-toggle'));

    // 카드의 되돌리기 버튼 클릭 → 확인모달.
    const revertBtn = await screen.findByRole('button', { name: '이 저장으로 되돌리기' });
    await user.click(revertBtn);

    const dialog = await screen.findByRole('dialog');
    await user.click(within(dialog).getByRole('button', { name: '되돌리기' }));

    // 작업본이 before(0,0,10,10) 으로 역적용됨.
    await waitFor(() =>
      expect(useLabelStore.getState().labels.find((l) => l.serverId === 100)?.shape).toEqual({
        type: 'BBOX',
        left: 0,
        top: 0,
        right: 10,
        bottom: 10,
      }),
    );
    // dirty 표시 — 저장 필요.
    expect(useLabelStore.getState().dirtyLabels.has('100')).toBe(true);
  });

  it('isLocked_영상은_되돌리기가_차단되고_에러토스트', async () => {
    // given — LOCKED_FOR_REDEIDENT 영상(비식별 재처리 중).
    mock.onGet('/frames/300/labels').reply(200, labelsPayload(300, { lockSttsCd: 'LOCKED_FOR_REDEIDENT' }));
    const user = userEvent.setup();
    renderWithProviders(<LabelingPage />, {
      initialEntries: ['/label/300'],
      routes: [{ path: '/label/:id', element: <LabelingPage /> }],
    });

    await waitFor(() => expect(screen.getByTestId('labeling-page')).toBeInTheDocument());
    await waitFor(() =>
      expect(useLabelStore.getState().labels.find((l) => l.serverId === 100)?.shape).toEqual({
        type: 'BBOX',
        left: 5,
        top: 5,
        right: 20,
        bottom: 20,
      }),
    );

    // when — 되돌리기 카드 버튼 → 확인모달 승인.
    await user.click(await screen.findByTestId('history-toggle'));
    await user.click(await screen.findByRole('button', { name: '이 저장으로 되돌리기' }));
    const dialog = await screen.findByRole('dialog');
    await user.click(within(dialog).getByRole('button', { name: '되돌리기' }));

    // then — 작업본은 역적용되지 않고(원 좌표 유지) 에러 토스트가 뜬다.
    await waitFor(() =>
      expect(useUiStore.getState().toasts.some((t) => t.variant === 'error')).toBe(true),
    );
    expect(useLabelStore.getState().labels.find((l) => l.serverId === 100)?.shape).toEqual({
      type: 'BBOX',
      left: 5,
      top: 5,
      right: 20,
      bottom: 20,
    });
    expect(useLabelStore.getState().dirtyLabels.has('100')).toBe(false);
  });

  it('되돌릴_항목이_작업본에_없으면_경고토스트_reverted0', async () => {
    // given — 히스토리 변경 대상 lblSn 이 현재 작업본에 없는 값(999).
    mock.onGet(/\/frames\/\d+\/label-history/).reply(200, historyPayload(999));
    const user = userEvent.setup();
    renderWithProviders(<LabelingPage />, {
      initialEntries: ['/label/300'],
      routes: [{ path: '/label/:id', element: <LabelingPage /> }],
    });

    await waitFor(() => expect(screen.getByTestId('labeling-page')).toBeInTheDocument());
    await waitFor(() => expect(useLabelStore.getState().labels.some((l) => l.serverId === 100)).toBe(true));

    // when — 되돌리기 카드 버튼 → 확인모달 승인.
    await user.click(await screen.findByTestId('history-toggle'));
    await user.click(await screen.findByRole('button', { name: '이 저장으로 되돌리기' }));
    const dialog = await screen.findByRole('dialog');
    await user.click(within(dialog).getByRole('button', { name: '되돌리기' }));

    // then — 역적용 0건 → 경고 토스트 + 작업본 좌표 불변.
    await waitFor(() =>
      expect(useUiStore.getState().toasts.some((t) => t.variant === 'warning')).toBe(true),
    );
    expect(useLabelStore.getState().labels.find((l) => l.serverId === 100)?.shape).toEqual({
      type: 'BBOX',
      left: 5,
      top: 5,
      right: 20,
      bottom: 20,
    });
    expect(useLabelStore.getState().dirtyLabels.has('100')).toBe(false);
  });
});
