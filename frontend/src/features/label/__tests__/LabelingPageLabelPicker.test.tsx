// 2026-08-03 사용자 확정 — 라벨링 화면 조작 흐름 변경 회귀 가드.
//
// ① 좌측 상시 라벨 패널 폐지(화면에 없다)
// ② 도형 도구 클릭 → 라벨 선택 모달 노출, 취소 시 도구 비활성(이전 도구로 복귀)
// ③ 같은 도구로 연속 작업 시 모달이 매번 뜨지 않는다(마지막 선택 라벨 유지)
// ④ 라벨 불필요 도구(선택 등)는 모달을 띄우지 않는다
// ⑤ KEYPOINT 배치 가이드는 좌측 패널 폐지 후에도 계속 보인다

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { act, fireEvent, screen, waitFor } from '@testing-library/react';

vi.mock('react-konva', async () => (await import('@/test/konvaMock')).createKonvaMock());

import { apiClient } from '@/lib/api/client';
import { LabelingPage } from '@/pages/label/LabelingPage';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';
import { useLabelStore } from '@/stores/useLabelStore';
import { ToolType } from '@/features/label/types';

const TEST_TOKEN = ['t', 'o', 'k'].join('');

const mastersPayload = {
  success: true,
  data: [
    { labelId: 11, name: '사람', color: '#EF4444', type: 'BBOX', sortNo: 1, useYn: 'Y', dtctTypeCd: 'person' },
    { labelId: 22, name: 'bus', color: '#3B82F6', type: 'BBOX', sortNo: 2, useYn: 'Y', dtctTypeCd: 'bus' },
  ],
  message: null,
  errorCode: null,
};

function labelsPayload() {
  return {
    success: true,
    data: {
      frameNo: 0,
      srcSn: 300,
      videoId: 7,
      siblings: [
        { srcSn: 300, frameNo: 0, hasLabel: false },
        { srcSn: 301, frameNo: 1, hasLabel: false },
      ],
      labels: [],
    },
    message: null,
    errorCode: null,
  };
}

function setup() {
  renderWithProviders(<LabelingPage />, {
    initialEntries: ['/label/300'],
    routes: [{ path: '/label/:id', element: <LabelingPage /> }],
  });
}

/** 라벨 로딩이 끝나 본문(도구바)이 렌더될 때까지 대기 — 로딩 화면에서 단언하면 위양성 통과. */
async function ready(): Promise<void> {
  await screen.findByRole('toolbar', { name: '라벨링 도구' });
}

/** 라벨 선택 모달 노출 여부 — 모달 제목 기준. */
function pickerVisible(): boolean {
  return screen.queryByRole('dialog', { name: '라벨 선택' }) !== null;
}

describe('LabelingPage — 라벨 선택 모달 흐름(2026-08-03 확정)', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    useLabelStore.getState().reset();
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
    mock.onGet('/manage/labels').reply(200, mastersPayload);
    mock.onGet(/\/videos\/\d+\/issues/).reply(200, { success: true, data: [], message: null, errorCode: null });
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
    useLabelStore.getState().reset();
    vi.restoreAllMocks();
  });

  it('①_좌측_상시_라벨_패널이_화면에_없다', async () => {
    setup();
    await ready();
    expect(screen.queryByRole('navigation', { name: '라벨 마스터' })).toBeNull();
  });

  it('②_도형_도구_클릭시_라벨_선택_모달이_뜬다', async () => {
    setup();
    await ready();
    expect(pickerVisible()).toBe(false);

    fireEvent.click(screen.getByRole('button', { name: '바운딩 박스' }));

    await waitFor(() => expect(pickerVisible()).toBe(true));
  });

  it('②_취소하면_도구가_활성화되지_않고_이전_도구로_복귀', async () => {
    setup();
    await ready();
    expect(useLabelStore.getState().activeTool).toBe(ToolType.SELECT);

    fireEvent.click(screen.getByRole('button', { name: '바운딩 박스' }));
    await waitFor(() => expect(pickerVisible()).toBe(true));

    fireEvent.click(screen.getByRole('button', { name: '취소' }));

    await waitFor(() => expect(pickerVisible()).toBe(false));
    expect(useLabelStore.getState().activeTool).toBe(ToolType.SELECT);
  });

  it('②_라벨_선택하면_도구가_활성화되고_activeLabelId가_기록된다', async () => {
    setup();
    await ready();

    fireEvent.click(screen.getByRole('button', { name: '바운딩 박스' }));
    await waitFor(() => expect(pickerVisible()).toBe(true));

    // 표시명은 마스터 등록명 그대로('bus') — 2026-08-03 재확정으로 한글 사전 치환 폐지.
    fireEvent.click(await screen.findByRole('button', { name: /bus/ }));

    await waitFor(() => expect(pickerVisible()).toBe(false));
    expect(useLabelStore.getState().activeTool).toBe(ToolType.BBOX);
    expect(useLabelStore.getState().activeLabelId).toBe(22);
  });

  it('③_같은_도구로_연속_작업시_모달이_다시_뜨지_않는다', async () => {
    setup();
    await ready();

    fireEvent.click(screen.getByRole('button', { name: '바운딩 박스' }));
    await waitFor(() => expect(pickerVisible()).toBe(true));
    fireEvent.click(await screen.findByRole('button', { name: /사람/ }));
    await waitFor(() => expect(pickerVisible()).toBe(false));

    // 도형을 그려도(라벨 추가) 모달은 다시 뜨지 않는다.
    act(() => {
      useLabelStore.getState().addLabel({
        id: 'l1',
        frameNo: 0,
        classId: 11,
        className: '사람',
        source: 'MANUAL',
        shape: { type: 'BBOX', left: 0, top: 0, right: 10, bottom: 10 },
      });
    });
    expect(pickerVisible()).toBe(false);
    expect(useLabelStore.getState().activeLabelId).toBe(11);
  });

  it('④_라벨이_필요없는_도구(선택)는_모달을_띄우지_않는다', async () => {
    setup();
    await ready();

    fireEvent.click(screen.getByRole('button', { name: '선택' }));
    expect(pickerVisible()).toBe(false);
    expect(useLabelStore.getState().activeTool).toBe(ToolType.SELECT);
  });

  it('④_단축키로_도구를_전환해도_같은_모달이_뜬다', async () => {
    setup();
    await ready();

    act(() => {
      window.dispatchEvent(new KeyboardEvent('keydown', { key: 'p', code: 'KeyP' }));
    });

    await waitFor(() => expect(pickerVisible()).toBe(true));
  });

  it('⑤_KEYPOINT_배치_가이드_자리는_우측패널로_이전됐다', async () => {
    setup();
    await ready();

    // 좌측 패널 폐지로 가이드가 사라지면 안 된다 — 우측 패널(탭 위, 항상 보이는 영역)로 이전.
    const slot = screen.getByTestId('keypoint-guide-slot');
    expect(screen.getByTestId('labeling-right-panel')).toContainElement(slot);
    // 가이드 본체는 배치 진행 중에만 렌더된다(미진행 → 슬롯만 존재).
    expect(screen.queryByRole('group', { name: '스켈레톤 배치 가이드' })).toBeNull();
  });
});
