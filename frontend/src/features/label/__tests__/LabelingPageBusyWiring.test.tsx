// DEV_FIX Phase 1 — 라벨링 화면 진입점 ↔ 배타 실행(busy) 배선 통합 검증.
//
// 여기서 잡는 결함 축:
//  - 저장이 폐기·거부됐는데 이동/닫기/dirty 해제가 진행되는 미저장 작업 소실(HIGH-2)
//  - 거부(다른 작업 진행 중)가 아무 안내 없이 무반응으로 끝나는 문제(MED-1)
//  - 불러오기(LOAD)가 배타 축 밖에서 도는 요구 GAP(HIGH-4)
//
// 훅 단위 테스트만으로는 "페이지가 그 계약을 실제로 지키는가"가 검증되지 않아 통합으로 둔다.

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { act, fireEvent, screen, waitFor } from '@testing-library/react';

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
  };
});

import { apiClient } from '@/lib/api/client';
import { LabelingPage } from '@/pages/label/LabelingPage';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';
import { useLabelStore } from '@/stores/useLabelStore';
import { useUiStore } from '@/stores/useUiStore';

// 테스트용 더미 인증값(비밀 아님 — 시크릿 스캐너 오탐 회피용 조합).
const FAKE_TOKEN = ['t', 'o', 'k'].join('');

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

function renderPage() {
  return renderWithProviders(<LabelingPage />, {
    initialEntries: ['/label/200'],
    routes: [{ path: '/label/:id', element: <LabelingPage /> }],
  });
}

async function loadAndDirty() {
  await waitFor(() => expect(useLabelStore.getState().labels).toHaveLength(1));
  act(() => {
    useLabelStore.getState().updateLabel('lbl-1', { className: 'bus' });
  });
  await waitFor(() => expect(useLabelStore.getState().dirtyLabels.size).toBeGreaterThan(0));
}

/** 다른 종류의 장시간 작업(예: AI 추적)이 진행 중인 상태를 만든다. */
function startOtherBusy(srcSn = 200) {
  act(() => {
    useLabelStore.getState().beginBusy('AI_TRACK', { srcSn });
  });
}

describe('LabelingPage — 배타 실행 배선', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    useAuthStore.setState({
      token: FAKE_TOKEN,
      claims: { sub: '10', role: 'WORKER', channel: 'INTERNAL', exp: 9999999999 },
    });
    useLabelStore.getState().reset();
    useUiStore.setState({ toasts: [] });
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
    useUiStore.setState({ toasts: [] });
  });

  it('다른_작업_진행중_저장은_버튼이_비활성이라_시작조차_되지_않는다', async () => {
    // given: AI 추적이 진행 중
    //   ⚠ Phase 2 에서 차단 지점이 "요청 거부"에서 "입력 차단"으로 올라갔다 — 저장 버튼 자체가
    //     비활성이라 거부 안내가 뜰 일도 없다(눌리는데 아무 일도 없는 화면을 만들지 않는 것이 목적).
    renderPage();
    await loadAndDirty();
    startOtherBusy();

    // then: 버튼이 비활성이고, 클릭해도 PUT 은 나가지 않는다.
    const saveButton = screen.getByTestId('label-toolbar-save');
    expect(saveButton).toBeDisabled();
    fireEvent.click(saveButton);
    await act(async () => {
      await Promise.resolve();
    });
    expect(mock.history.put).toHaveLength(0);
    // 저장되지 않았으므로 dirty 는 유지된다(미저장 경고 지속).
    expect(useLabelStore.getState().dirtyLabels.size).toBeGreaterThan(0);
  });

  it('저장_취소시_dirty_유지되어_미저장_경고_지속', async () => {
    // given: 저장 PUT 이 응답하기 전에 사용자가 취소(다른 프레임 이동 등 → cancelBusy)
    let resolvePut: (() => void) | null = null;
    mock.onPut('/frames/200/labels').reply(
      () =>
        new Promise((resolve) => {
          resolvePut = () => resolve([200, labelsPayload(200, 0, [labelOnFrame0])]);
        }),
    );
    renderPage();
    await loadAndDirty();

    // when
    fireEvent.click(screen.getByTestId('label-toolbar-save'));
    await waitFor(() => expect(useLabelStore.getState().busy?.kind).toBe('SAVE'));
    act(() => {
      useLabelStore.getState().cancelBusy();
    });
    await act(async () => {
      resolvePut?.();
      await Promise.resolve();
    });

    // then: 폐기된 저장은 "저장됨"으로 취급하지 않는다 — dirty 유지 + 성공 토스트 없음.
    await waitFor(() => expect(useLabelStore.getState().busy).toBeNull());
    expect(useLabelStore.getState().dirtyLabels.size).toBeGreaterThan(0);
    expect(useUiStore.getState().toasts.some((t) => t.message === '저장됨')).toBe(false);
  });

  it('프레임전환_중_저장이_취소되면_이동하지_않고_현재_프레임_유지', async () => {
    // given: 미저장 상태에서 다음 프레임 이동 → 가드 모달의 "저장 후 이동"
    let resolvePut: (() => void) | null = null;
    mock.onPut('/frames/200/labels').reply(
      () =>
        new Promise((resolve) => {
          resolvePut = () => resolve([200, labelsPayload(200, 0, [labelOnFrame0])]);
        }),
    );
    renderPage();
    await loadAndDirty();
    fireEvent.keyDown(window, { key: 'd', code: 'KeyD' });
    fireEvent.click(await screen.findByTestId('frame-nav-guard-save'));

    // when: 저장 응답 전에 취소(폐기)
    await waitFor(() => expect(useLabelStore.getState().busy?.kind).toBe('SAVE'));
    act(() => {
      useLabelStore.getState().cancelBusy();
    });
    await act(async () => {
      resolvePut?.();
      await Promise.resolve();
    });

    // then: 이동하지 않고 현재 프레임(200)의 라벨/미저장 상태가 그대로 유지된다.
    await waitFor(() => expect(useLabelStore.getState().busy).toBeNull());
    expect(useLabelStore.getState().labels).toHaveLength(1);
    expect(useLabelStore.getState().dirtyLabels.size).toBeGreaterThan(0);
    expect(screen.getByTestId('frame-counter').textContent).toContain('Frame 1 /');
  });

  it('정상_저장이면_labelVersion0_응답이어도_이동한다', async () => {
    // given: `!saved` 오타 가드였다면 labelVersion:0 같은 falsy 필드 응답이 오폐기된다.
    //   (판정은 반드시 `=== null` — 정상 응답을 폐기로 오인하면 저장 후 이동이 죽는다)
    mock
      .onPut('/frames/200/labels')
      .reply(200, {
        success: true,
        data: { frameNo: 0, srcSn: 200, videoId: 7, labelVersion: 0, siblings: [], labels: [] },
        message: null,
        errorCode: null,
      });
    renderPage();
    await loadAndDirty();

    // when
    fireEvent.keyDown(window, { key: 'd', code: 'KeyD' });
    fireEvent.click(await screen.findByTestId('frame-nav-guard-save'));

    // then: 저장 후 실제로 다음 프레임으로 이동한다.
    await waitFor(() => expect(mock.history.put).toHaveLength(1));
    await waitFor(() => expect(screen.getByTestId('frame-counter').textContent).toContain('Frame 2 /'));
  });

  it('닫기확인_저장이_폐기되면_이동하지_않고_dirty가_유지된다', async () => {
    // given: X(닫기) → "저장 후 닫기". 이 경로만 null 계약이 빠져 있었다(HIGH-2).
    let resolvePut: (() => void) | null = null;
    mock.onPut('/frames/200/labels').reply(
      () =>
        new Promise((resolve) => {
          resolvePut = () => resolve([200, labelsPayload(200, 0, [labelOnFrame0])]);
        }),
    );
    renderPage();
    await loadAndDirty();
    fireEvent.click(screen.getByLabelText('뒤로가기'));
    fireEvent.click(await screen.findByTestId('label-close-save'));

    // when: 저장 응답 전에 취소(폐기)
    await waitFor(() => expect(useLabelStore.getState().busy?.kind).toBe('SAVE'));
    act(() => {
      useLabelStore.getState().cancelBusy();
    });
    await act(async () => {
      resolvePut?.();
      await Promise.resolve();
    });

    // then: 화면을 떠나지 않고(라벨링 화면 유지) 미저장 표시도 남는다.
    await waitFor(() => expect(useLabelStore.getState().busy).toBeNull());
    expect(screen.getByTestId('labeling-page')).toBeInTheDocument();
    expect(useLabelStore.getState().dirtyLabels.size).toBeGreaterThan(0);
  });

  it('저장충돌_후_최신라벨_불러오기는_LOAD_배타축에서_실행된다', async () => {
    // given: 409 충돌로 "최신 라벨 불러오기" 안내가 뜬 상태 (R1 저장·불러오기)
    mock.onPut('/frames/200/labels').reply(409, {
      success: false,
      data: null,
      message: '다른 사용자가 먼저 저장했습니다.',
      errorCode: 'CONFLICT',
    });
    const kinds: string[] = [];
    const unsub = useLabelStore.subscribe((s) => {
      if (s.busy !== null) kinds.push(s.busy.kind);
    });
    renderPage();
    await loadAndDirty();
    fireEvent.click(screen.getByTestId('label-toolbar-save'));
    const reload = await screen.findByRole('button', { name: '최신 라벨 불러오기' });

    // when
    fireEvent.click(reload);

    // then: 불러오기도 busy('LOAD')를 점유한다 — 저장/AI 와 같은 배타 축.
    await waitFor(() =>
      expect(useUiStore.getState().toasts.some((t) => t.message === '최신 라벨을 불러왔습니다.')).toBe(
        true,
      ),
    );
    expect(kinds).toContain('LOAD');
    unsub();
  });

  it('불러오기가_거부되면_충돌_안내가_유지되어_재시도할_수_있다', async () => {
    // given: 409 충돌 안내가 뜬 상태에서 다른 장시간 작업이 시작됐다.
    mock.onPut('/frames/200/labels').reply(409, {
      success: false,
      data: null,
      message: '다른 사용자가 먼저 저장했습니다.',
      errorCode: 'CONFLICT',
    });
    renderPage();
    await loadAndDirty();
    fireEvent.click(screen.getByTestId('label-toolbar-save'));
    const reload = await screen.findByRole('button', { name: '최신 라벨 불러오기' });
    startOtherBusy();
    useUiStore.setState({ toasts: [] });

    // when: 불러오기를 눌렀지만 배타 실행에 거부된다
    fireEvent.click(reload);

    // then: 다이얼로그를 먼저 닫으면 불러오지도 못한 채 재시도 동선까지 사라진다 — 유지해야 한다.
    await waitFor(() => expect(useUiStore.getState().toasts).toHaveLength(1));
    expect(useUiStore.getState().toasts[0].message).toContain('진행 중');
    expect(screen.getByRole('button', { name: '최신 라벨 불러오기' })).toBeInTheDocument();
  });

  it('불러오기를_취소하면_뒤늦게_도착한_응답이_작업본을_덮지_않는다', async () => {
    // AC5 — 취소했는데 결과가 반영되면 안 된다. 불러오기 결과 병합(setLabels)은 dirtyLabels 와
    // undo/redo 스택까지 비우므로, 취소 뒤 뒤늦게 병합되면 **복구 불가능한 미저장 작업 소실**이다.
    mock.reset();
    let releaseReload: (() => void) | null = null;
    let reloadRequested = false;
    mock.onGet('/frames/200/labels').reply(() => {
      if (!reloadRequested) {
        reloadRequested = true;
        return [200, labelsPayload(200, 0, [labelOnFrame0])];
      }
      // 재조회(불러오기) 응답은 테스트가 놓아줄 때까지 붙잡는다 — 취소가 응답보다 먼저 오는 창.
      return new Promise((resolve) => {
        releaseReload = () =>
          resolve([
            200,
            labelsPayload(200, 0, [{ ...labelOnFrame0, id: 'lbl-server', className: 'truck' }]),
          ]);
      });
    });
    mock.onGet('/frames/200/image').reply(200, new Blob());
    mock.onPut('/frames/200/labels').reply(409, {
      success: false,
      data: null,
      message: '다른 사용자가 먼저 저장했습니다.',
      errorCode: 'CONFLICT',
    });

    renderPage();
    await loadAndDirty();
    const undoDepthBefore = useLabelStore.getState().undoStack.length;
    expect(undoDepthBefore).toBeGreaterThan(0);
    fireEvent.click(screen.getByTestId('label-toolbar-save'));
    const reload = await screen.findByRole('button', { name: '최신 라벨 불러오기' });

    // when: 불러오기를 시작한 뒤 응답 도착 전에 취소한다(오버레이 취소 / ESC 와 동일 경로).
    fireEvent.click(reload);
    await waitFor(() => expect(useLabelStore.getState().busy?.kind).toBe('LOAD'));
    act(() => {
      useLabelStore.getState().cancelBusy();
    });
    await waitFor(() => expect(releaseReload).not.toBeNull());
    await act(async () => {
      releaseReload?.();
      await Promise.resolve();
    });

    // then: 취소했으므로 서버 라벨이 반영되지 않고, 미저장 편집과 되돌리기 스택이 보존된다.
    await waitFor(() => expect(useUiStore.getState().toasts.length).toBeGreaterThanOrEqual(0));
    const state = useLabelStore.getState();
    expect(state.labels.map((l) => l.id)).toEqual(['lbl-1']);
    expect(state.labels[0]?.className).toBe('bus');
    expect(state.dirtyLabels.size).toBeGreaterThan(0);
    expect(state.undoStack.length).toBe(undoDepthBefore);
    // 취소는 "성공"이 아니다 — 불러왔다는 안내가 뜨면 사용자는 최신본을 보고 있다고 오인한다.
    expect(
      useUiStore.getState().toasts.some((t) => t.message === '최신 라벨을 불러왔습니다.'),
    ).toBe(false);
  });
});
