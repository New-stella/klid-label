// H-ISSUE-41 / H-ISSUE-42 — 서버 잠금 코드(lockSttsCd) BE↔FE 계약 회귀 가드.
//
// 배경: BE(LabelService.getByFrame)가 락 행 상태값 'LOCKED' 를 응답에 실어 FE 판정 상수
// ('LOCKED_FOR_REDEIDENT')와 어긋났고, 그 결과 ①서버 단독 잠금(신고 없이 락만 걸린 상태)에서
// 잠금 배너가 뜨지 않았고 ②저장 시점에야 BE 최종 방어에 걸려 "다른 사용자가 먼저 저장했습니다"
// 류의 부정확한 안내로 흘렀다. 값 정정 이후의 계약을 화면 동작으로 고정한다.

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { fireEvent, screen, waitFor } from '@testing-library/react';

vi.mock('react-konva', async () => (await import('@/test/konvaMock')).createKonvaMock());

import { apiClient } from '@/lib/api/client';
import { LockSttsCd } from '@/features/label/types';
import { LabelingPage } from '@/pages/label/LabelingPage';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';
import { useLabelStore } from '@/stores/useLabelStore';
import { useUiStore } from '@/stores/useUiStore';

// 테스트용 더미 인증값(비밀 아님).
const FAKE_TOKEN = ['t', 'o', 'k'].join('');
const SRC_SN = 410;

const labelOnFrame0 = {
  id: 'lbl-1',
  frameNo: 0,
  classId: 1,
  className: 'car',
  source: 'MANUAL',
  shape: { type: 'BBOX', left: 0, top: 0, right: 50, bottom: 30 },
};

function labelsPayload(lockSttsCd: string | null) {
  return {
    success: true,
    data: {
      frameNo: 0,
      srcSn: SRC_SN,
      videoId: 7,
      frameImageType: 'DEID',
      labelVersion: 3,
      lockSttsCd,
      siblings: [{ srcSn: SRC_SN, frameNo: 0 }],
      labels: [labelOnFrame0],
    },
    message: null,
    errorCode: null,
  };
}

describe('lockSttsCd BE↔FE 계약', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    useAuthStore.setState({
      token: FAKE_TOKEN,
      claims: { sub: '10', role: 'WORKER', channel: 'INTERNAL', exp: 9999999999 },
    });
    mock.onGet(`/frames/${SRC_SN}/image`).reply(200, new Blob());
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
    useLabelStore.getState().reset();
    useUiStore.setState({ toasts: [] });
  });

  function renderPage() {
    return renderWithProviders(<LabelingPage />, {
      initialEntries: [`/label/${SRC_SN}`],
      routes: [{ path: '/label/:id', element: <LabelingPage /> }],
    });
  }

  it('FE는_lockSttsCd가_LOCKED_FOR_REDEIDENT일_때_잠금_배너를_표시한다', async () => {
    // given — 신고 POST 없이 서버가 단독으로 잠금 코드를 내려준 응답(서버 단독 잠금).
    mock.onGet(`/frames/${SRC_SN}/labels`).reply(200, labelsPayload(LockSttsCd.LOCKED_FOR_REDEIDENT));

    // when
    renderPage();

    // then — 배너 노출 + 저장 버튼 비활성 + 신고 버튼 비활성(이미 잠금).
    const banner = await screen.findByTestId('deident-locked-banner');
    expect(banner).toHaveTextContent(/비식별 재처리 중/);
    expect(screen.getByTestId('label-toolbar-save')).toBeDisabled();
    expect(screen.getByRole('button', { name: /비식별 누락 신고/ })).toBeDisabled();
  });

  it('서버_단독_잠금에서_저장_시도는_잠금_안내이며_저장충돌_다이얼로그가_아니다', async () => {
    // given — 잠금 상태. PUT 이 나가면 BE 는 409 로 거부하지만, FE 는 그 전에 원인을 구분해야 한다.
    mock.onGet(`/frames/${SRC_SN}/labels`).reply(200, labelsPayload(LockSttsCd.LOCKED_FOR_REDEIDENT));
    mock.onPut(`/frames/${SRC_SN}/labels`).reply(409, {
      success: false,
      data: null,
      message: '다른 사용자가 먼저 저장했습니다.',
      errorCode: 'CONFLICT',
    });
    renderPage();
    await screen.findByTestId('deident-locked-banner');

    // when — 저장 버튼은 비활성이므로 단축키(Ctrl+S)로 저장을 시도한다.
    fireEvent.keyDown(window, { key: 's', code: 'KeyS', ctrlKey: true });

    // then — 잠금 안내 토스트. PUT 미발생 + 저장충돌 다이얼로그 미노출(원인 혼동 없음).
    await waitFor(() => {
      expect(
        useUiStore
          .getState()
          .toasts.some((t) => t.message === '비식별 재처리 중인 영상은 저장할 수 없습니다.'),
      ).toBe(true);
    });
    expect(mock.history.put.filter((r) => r.url === `/frames/${SRC_SN}/labels`)).toHaveLength(0);
    expect(screen.queryByRole('button', { name: '최신 라벨 불러오기' })).toBeNull();
  });

  it('FE_판정_상수는_BE_응답_계약값과_동일한_문자열이다', () => {
    // BE(LabelResponse.LOCK_STTS_LOCKED_FOR_REDEIDENT) 와 동치. BE 쪽에서도 이 파일을 읽어
    // 대조한다(LabelServiceLockSttsCdTest#BE_응답_상수와_FE_판정_상수가_동일한_문자열이다).
    expect(LockSttsCd.LOCKED_FOR_REDEIDENT).toBe('LOCKED_FOR_REDEIDENT');
  });

  it('잠금이_아닌_저장충돌_409는_기존대로_충돌_안내로_구분된다', async () => {
    // given — 잠금 없음. 낙관적 동시성 충돌만 발생한 경우.
    mock.onGet(`/frames/${SRC_SN}/labels`).reply(200, labelsPayload(null));
    mock.onPut(`/frames/${SRC_SN}/labels`).reply(409, {
      success: false,
      data: null,
      message: '다른 사용자가 먼저 저장했습니다.',
      errorCode: 'CONFLICT',
    });
    renderPage();
    await waitFor(() => expect(useLabelStore.getState().labels).toHaveLength(1));

    // when
    fireEvent.click(await screen.findByTestId('label-toolbar-save'));

    // then — 잠금 안내가 아니라 저장충돌 안내(두 원인이 서로 다른 UI 로 구분된다).
    expect(await screen.findByRole('button', { name: '최신 라벨 불러오기' })).toBeInTheDocument();
    expect(
      useUiStore
        .getState()
        .toasts.some((t) => t.message === '비식별 재처리 중인 영상은 저장할 수 없습니다.'),
    ).toBe(false);
  });
});
