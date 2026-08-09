// R5 — LabelingPage 프레임 이동 미저장 가드.
// dirtyLabels.size>0 이면 프레임 이동 요청 시 3옵션 가드 모달(저장 후 이동/저장 안 함/취소).
// dirty 없으면 즉시 이동. 단축키 프레임 이동(W/A/S/D)도 동일 가드 경유.

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { fireEvent, screen, waitFor } from '@testing-library/react';

vi.mock('react-konva', async () => (await import('@/test/konvaMock')).createKonvaMock());

import { apiClient } from '@/lib/api/client';
import { LabelingPage } from '@/pages/label/LabelingPage';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';
import { useLabelStore } from '@/stores/useLabelStore';

function labelsPayload(srcSn: number, frameNo: number, labels: unknown[] = []) {
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

function renderPage() {
  return renderWithProviders(<LabelingPage />, {
    initialEntries: ['/label/200'],
    routes: [{ path: '/label/:id', element: <LabelingPage /> }],
  });
}

/** 초기 프레임(200) 로드 완료 후 dirty 를 만든다. */
async function loadAndDirty() {
  await waitFor(() => {
    expect(useLabelStore.getState().labels).toHaveLength(1);
  });
  // 라벨 수정 → dirtyLabels 마킹
  useLabelStore.getState().updateLabel('lbl-1', { className: 'bus' });
  await waitFor(() => {
    expect(useLabelStore.getState().dirtyLabels.size).toBeGreaterThan(0);
  });
}

/** D 단축키 = 다음 프레임 이동 요청. */
function pressNextFrame() {
  fireEvent.keyDown(window, { key: 'd', code: 'KeyD' });
}

describe('LabelingPage — 프레임 이동 미저장 가드', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    useAuthStore.setState({
      token: 'tok',
      claims: { sub: '10', role: 'WORKER', channel: 'INTERNAL', exp: 9999999999 },
    });
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

  it('dirty상태_프레임이동시_확인모달_노출', async () => {
    renderPage();
    await loadAndDirty();

    pressNextFrame();

    expect(await screen.findByTestId('frame-nav-guard-save')).toBeInTheDocument();
    // 아직 이동하지 않음 — 라벨 그대로
    expect(useLabelStore.getState().labels).toHaveLength(1);
  });

  it('dirty없으면_즉시_이동', async () => {
    renderPage();
    await waitFor(() => {
      expect(useLabelStore.getState().labels).toHaveLength(1);
    });
    // dirty 없음 상태에서 이동
    pressNextFrame();

    // 가드 모달 미노출 + 프레임 201(빈 라벨)로 즉시 이동
    expect(screen.queryByTestId('frame-nav-guard-save')).toBeNull();
    await waitFor(() => {
      expect(useLabelStore.getState().labels).toHaveLength(0);
    });
  });

  it('취소_선택시_현재프레임_유지', async () => {
    renderPage();
    await loadAndDirty();
    pressNextFrame();
    const cancel = await screen.findByTestId('frame-nav-guard-cancel');

    fireEvent.click(cancel);

    await waitFor(() => {
      expect(screen.queryByTestId('frame-nav-guard-cancel')).toBeNull();
    });
    // 현재 프레임 유지 — 라벨/ dirty 보존
    expect(useLabelStore.getState().labels).toHaveLength(1);
    expect(useLabelStore.getState().dirtyLabels.size).toBeGreaterThan(0);
  });

  it('저장안함_선택시_clearDirty후_이동', async () => {
    renderPage();
    await loadAndDirty();
    pressNextFrame();
    const discard = await screen.findByTestId('frame-nav-guard-discard');

    fireEvent.click(discard);

    // 프레임 201(빈 라벨)로 이동
    await waitFor(() => {
      expect(useLabelStore.getState().labels).toHaveLength(0);
    });
    expect(useLabelStore.getState().dirtyLabels.size).toBe(0);
  });

  it('저장후이동_선택시_save성공후_이동', async () => {
    let putCalled = false;
    mock.onPut('/frames/200/labels').reply(() => {
      putCalled = true;
      return [200, labelsPayload(200, 0, [labelOnFrame0])];
    });
    renderPage();
    await loadAndDirty();
    pressNextFrame();
    const save = await screen.findByTestId('frame-nav-guard-save');

    fireEvent.click(save);

    await waitFor(() => {
      expect(putCalled).toBe(true);
    });
    // 저장 성공 → 프레임 201 이동
    await waitFor(() => {
      expect(useLabelStore.getState().labels).toHaveLength(0);
    });
  });

  it('가드모달_열림중_프레임이동_삭제_단축키가_억제된다', async () => {
    renderPage();
    await loadAndDirty();
    // 현재 프레임 라벨 선택 — 삭제 단축키(R) 대상.
    useLabelStore.getState().selectLabel('lbl-1');
    pressNextFrame();
    // 가드 모달 노출
    await screen.findByTestId('frame-nav-guard-save');

    // 모달 열린 동안: R(삭제)·W(첫프레임) 단축키 발화 시도 → 모두 no-op 이어야 함.
    fireEvent.keyDown(window, { key: 'r', code: 'KeyR' });
    fireEvent.keyDown(window, { key: 'w', code: 'KeyW' });

    // 라벨 삭제 안 됨 + 배경 프레임 이동 안 됨(현재 프레임 유지) + 모달 유지.
    expect(useLabelStore.getState().labels).toHaveLength(1);
    expect(screen.getByTestId('frame-nav-guard-save')).toBeInTheDocument();
  });

  it('저장후이동_save실패시_이동취소_현재프레임유지', async () => {
    mock.onPut('/frames/200/labels').reply(500);
    renderPage();
    await loadAndDirty();
    pressNextFrame();
    const save = await screen.findByTestId('frame-nav-guard-save');

    fireEvent.click(save);

    // 모달 닫힘 + 현재 프레임(200, 라벨 1개) 유지 (이동 취소)
    await waitFor(() => {
      expect(screen.queryByTestId('frame-nav-guard-save')).toBeNull();
    });
    expect(useLabelStore.getState().labels).toHaveLength(1);
  });
});
