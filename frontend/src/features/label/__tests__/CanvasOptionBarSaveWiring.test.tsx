// 캔버스 상단 옵션바 저장 배선 — 이관 후에도 저장이 **같은 절차**를 탄다는 계약.
//
// ① 낙관적 동시성 토큰(labelVersion)이 PUT 바디에 실린다. 빠지면 BE 가 검사를 건너뛰고,
//    그사이 다른 사용자가 추가한 라벨이 full-replace 로 조용히 삭제된다(lost update).
// ② Ctrl+S 단축키가 버튼과 같은 저장을 수행한다(이관으로 단축키가 끊기지 않았는지).
//
// ⚠ 이 파일이 **못 보는 것**: ①은 옵션바가 자체 저장으로 되돌아가도 통과한다 —
//   useUpdateLabels 가 조회 캐시의 labelVersion 을 읽어 채우기 때문이다. 즉 "화면이 저장을
//   소유한다"는 위임 자체를 판별하지는 못한다. 그 축은 **409 저장 충돌 안내**가 판별하며
//   (lockSttsCdContract.test.tsx · LabelingPageBusyWiring.test.tsx — 자체 저장으로 바꾸면
//   충돌 다이얼로그 대신 버튼 내부 alert 로 새어 4건이 실패한다), **포털 라우팅**은
//   PortalLabelingDataPath.test.tsx 가 판별한다. 세 파일이 함께 있어야 계약이 닫힌다.

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { fireEvent, screen, waitFor } from '@testing-library/react';

vi.mock('react-konva', async () => (await import('@/test/konvaMock')).createKonvaMock());

import { apiClient } from '@/lib/api/client';
import { LabelingPage } from '@/pages/label/LabelingPage';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';
import { useLabelStore } from '@/stores/useLabelStore';

// 테스트용 더미 인증값(비밀 아님).
const FAKE_TOKEN = ['t', 'o', 'k'].join('');
const SRC_SN = 411;
const LABEL_VERSION = 17;

const labelOnFrame0 = {
  id: 'lbl-1',
  frameNo: 0,
  classId: 1,
  className: 'car',
  source: 'MANUAL',
  shape: { type: 'BBOX', left: 0, top: 0, right: 50, bottom: 30 },
};

describe('캔버스 상단 옵션바 — 저장 배선', () => {
  let mock: MockAdapter;
  let sentVersion: number | 'NOT_SENT' | null = null;

  beforeEach(() => {
    sentVersion = null;
    mock = new MockAdapter(apiClient);
    useAuthStore.setState({
      token: FAKE_TOKEN,
      claims: { sub: '10', role: 'WORKER', channel: 'INTERNAL', exp: 9999999999 },
    });
    useLabelStore.getState().reset();

    mock.onGet(`/frames/${SRC_SN}/labels`).reply(200, {
      success: true,
      data: {
        frameNo: 0,
        srcSn: SRC_SN,
        videoId: 7,
        frameImageType: 'DEID',
        labelVersion: LABEL_VERSION,
        lockSttsCd: null,
        siblings: [{ srcSn: SRC_SN, frameNo: 0 }],
        labels: [labelOnFrame0],
      },
      message: null,
      errorCode: null,
    });
    mock.onGet(`/frames/${SRC_SN}/image`).reply(200, new Blob([new Uint8Array([1, 2, 3])]));
    mock.onPut(`/frames/${SRC_SN}/labels`).reply((config) => {
      const body = JSON.parse(config.data as string) as { labelVersion?: number };
      sentVersion = 'labelVersion' in body ? (body.labelVersion as number) : 'NOT_SENT';
      return [
        200,
        {
          success: true,
          data: {
            frameNo: 0,
            srcSn: SRC_SN,
            labelVersion: LABEL_VERSION + 1,
            siblings: [],
            labels: [],
          },
          message: null,
          errorCode: null,
        },
      ];
    });
    mock.onAny().reply(200, { success: true, data: null, message: null, errorCode: null });
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
    useLabelStore.getState().reset();
    vi.restoreAllMocks();
  });

  function renderPage() {
    return renderWithProviders(<LabelingPage />, {
      initialEntries: [`/label/${SRC_SN}`],
      routes: [{ path: '/label/:id', element: <LabelingPage /> }],
    });
  }

  it('★옵션바_저장_버튼이_낙관적_동시성_토큰을_함께_보낸다', async () => {
    renderPage();
    await waitFor(() => expect(useLabelStore.getState().labels).toHaveLength(1));

    const bar = await screen.findByTestId('canvas-option-bar');
    const save = screen.getByRole('button', { name: '저장' });
    // 저장 버튼이 옵션바 안에 있다(위치 계약).
    expect(bar.contains(save)).toBe(true);

    fireEvent.click(save);

    await waitFor(() => expect(sentVersion).not.toBeNull());
    // 토큰을 빠뜨리면 BE 가 검사를 건너뛰어 다른 사용자의 라벨이 조용히 삭제된다.
    expect(sentVersion).toBe(LABEL_VERSION);
  });

  it('Ctrl_S_단축키도_같은_저장_절차를_탄다_이관_후에도_동일_동작', async () => {
    renderPage();
    await waitFor(() => expect(useLabelStore.getState().labels).toHaveLength(1));

    fireEvent.keyDown(window, { key: 's', code: 'KeyS', ctrlKey: true });

    await waitFor(() => expect(sentVersion).toBe(LABEL_VERSION));
  });
});
