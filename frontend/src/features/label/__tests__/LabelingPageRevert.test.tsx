// LabelingPage — 저장 이벤트 되돌리기 확인모달 → 작업본 역적용 플로우.
//
// 히스토리 패널 "변경 이력" 탭의 저장 이벤트 카드 [되돌리기] → 확인모달 → 승인 시
// 스토어 작업본에 역적용(UPDATED before 복원) + dirty 표시(저장으로 확정).
//
// ⚠ 진입 경로가 바뀌었다(R6/D4) — 헤더 [히스토리] 버튼이 폐지되고, 그 패널은 캔버스 상단 옵션바의
//   [버전] 버튼이 여는 「시작 버전 선택」 모달 안으로 **재배치**됐다. 되돌리기 기능 자체는 그대로다.

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

vi.mock('react-konva', async () => (await import('@/test/konvaMock')).createKonvaMock());

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

/**
 * 재배치된 "변경 이력" 탭까지 연다 — [버전] → 프레임 버전 이력 펼침 → '변경 이력' 탭.
 * 구 경로(헤더 [히스토리] 한 번)와 달리 세 단계이지만 도달 대상은 동일한 패널이다.
 */
async function openChangeHistory(user: ReturnType<typeof userEvent.setup>) {
  await user.click(await screen.findByTestId('start-version-open'));
  await user.click(await screen.findByTestId('start-version-history-toggle'));
  await user.click(await screen.findByTestId('history-tab-changes'));
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
    await openChangeHistory(user);

    // 카드의 되돌리기 버튼 클릭 → 확인모달.
    const revertBtn = await screen.findByRole('button', { name: '이 저장으로 되돌리기' });
    await user.click(revertBtn);

    // ⚠ 「시작 버전 선택」 모달 위에 확인 다이얼로그가 겹쳐 뜬다 — 이름으로 특정한다.
    const dialog = await screen.findByRole('dialog', { name: '이 저장으로 되돌리기' });
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
    await openChangeHistory(user);
    await user.click(await screen.findByRole('button', { name: '이 저장으로 되돌리기' }));
    // ⚠ 「시작 버전 선택」 모달 위에 확인 다이얼로그가 겹쳐 뜬다 — 이름으로 특정한다.
    const dialog = await screen.findByRole('dialog', { name: '이 저장으로 되돌리기' });
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

  it('busy_중에는_되돌리기_버튼이_비활성이다', async () => {
    // 되돌리기는 작업본(labels/dirty)을 바꾸는 편집이다 — 저장 in-flight 중에 실행되면 저장 성공
    // 시 clearDirty() 가 되돌린 분의 미저장 표식까지 지워 이탈 경고·프레임 가드가 풀린다.
    const user = userEvent.setup();
    renderWithProviders(<LabelingPage />, {
      initialEntries: ['/label/300'],
      routes: [{ path: '/label/:id', element: <LabelingPage /> }],
    });

    await waitFor(() => expect(screen.getByTestId('labeling-page')).toBeInTheDocument());
    await openChangeHistory(user);
    const revertBtn = await screen.findByRole('button', { name: '이 저장으로 되돌리기' });
    expect(revertBtn).not.toBeDisabled();

    act(() => {
      useLabelStore.getState().beginBusy('SAVE', { srcSn: 300 });
    });

    await waitFor(() =>
      expect(screen.getByRole('button', { name: '이 저장으로 되돌리기' })).toBeDisabled(),
    );

    // 해제되면 즉시 복구된다.
    act(() => {
      useLabelStore.getState().cancelBusy();
    });
    await waitFor(() =>
      expect(screen.getByRole('button', { name: '이 저장으로 되돌리기' })).not.toBeDisabled(),
    );
  });

  it('확인모달을_연_뒤_busy가_시작되면_되돌리기_승인이_차단된다', async () => {
    // 이중 방어 — 버튼 비활성화만으로는 "모달을 먼저 열어둔" 경로가 열려 있다.
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
    await openChangeHistory(user);
    await user.click(await screen.findByRole('button', { name: '이 저장으로 되돌리기' }));
    // ⚠ 「시작 버전 선택」 모달 위에 확인 다이얼로그가 겹쳐 뜬다 — 이름으로 특정한다.
    const dialog = await screen.findByRole('dialog', { name: '이 저장으로 되돌리기' });

    // when: 모달이 열린 뒤 저장이 시작되고, 그 상태로 승인.
    act(() => {
      useLabelStore.getState().beginBusy('SAVE', { srcSn: 300 });
    });
    await user.click(within(dialog).getByRole('button', { name: '되돌리기' }));

    // then: 작업본은 그대로고 무음이 아니다.
    expect(useLabelStore.getState().labels.find((l) => l.serverId === 100)?.shape).toEqual({
      type: 'BBOX',
      left: 5,
      top: 5,
      right: 20,
      bottom: 20,
    });
    expect(useLabelStore.getState().dirtyLabels.has('100')).toBe(false);
    const toasts = useUiStore.getState().toasts;
    expect(toasts.some((t) => t.variant === 'warning' && t.message.includes('진행 중'))).toBe(true);
    // 사용자 노출 문구에 모델명 금지.
    expect(toasts.every((t) => !/SAM|YOLO/i.test(t.message))).toBe(true);
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
    await openChangeHistory(user);
    await user.click(await screen.findByRole('button', { name: '이 저장으로 되돌리기' }));
    // ⚠ 「시작 버전 선택」 모달 위에 확인 다이얼로그가 겹쳐 뜬다 — 이름으로 특정한다.
    const dialog = await screen.findByRole('dialog', { name: '이 저장으로 되돌리기' });
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
