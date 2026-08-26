// @design SCREEN-029 — 포털 라벨링 화면의 프레임 이동 경로 회귀 가드.
//
// LabelingPage 는 내부(`/label/:id`)와 포털(`/portal/label/:id`) 두 라우트가 재사용하는
// 단일 컴포넌트다. 프레임 이동 경로를 내부 경로로 고정하면 포털 사용자는 프레임을 넘기는
// 순간 INTERNAL 채널 가드에 걸려 접근 거부 화면으로 튕기고, 포털 라벨링이 첫 프레임 한 장으로
// 제한된다(실제 사용자 신고 결함). 그 동선에 회귀 가드가 없어 결함이 살아남은 구간이라
// 채널별 이동 경로와 replace 시맨틱을 여기서 고정한다.
//
// ⚠ 이 파일만 react-router-dom 의 useNavigate 를 스파이로 바꾼다 — 이동 "경로 문자열"과
//   `replace: true` 를 함께 단언해야 하는데, 실제 라우터로는 replace 여부를 관측할 수 없다.
//   같은 계열의 기존 포털 회귀 테스트에 이 모듈 모의를 넣으면 그 파일의 다른 케이스까지
//   라우팅이 무력화되므로 파일을 분리했다.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, screen, waitFor } from '@testing-library/react';
import MockAdapter from 'axios-mock-adapter';

vi.mock('react-konva', async () => (await import('@/test/konvaMock')).createKonvaMock());

const navigateSpy = vi.fn();
vi.mock('react-router-dom', async (importOriginal) => {
  const actual = await importOriginal<typeof import('react-router-dom')>();
  return { ...actual, useNavigate: () => navigateSpy };
});

import { apiClient } from '@/lib/api/client';
import { LabelingPage } from '@/pages/label/LabelingPage';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';

// 프레임 2장짜리 영상 — 1번(현재)에서 2번으로 넘긴다.
function labelsPayload(srcSn: number) {
  return {
    success: true,
    data: {
      frameNo: srcSn === 777 ? 1 : 2,
      srcSn,
      videoId: 7,
      siblings: [
        { srcSn: 777, frameNo: 1 },
        { srcSn: 778, frameNo: 2 },
      ],
      labels: [],
    },
    message: null,
    errorCode: null,
  };
}

describe('라벨링 프레임 이동 경로 — 채널별 분기', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    navigateSpy.mockClear();
    mock = new MockAdapter(apiClient);
    // 내부/포털 두 경로 모두 응답을 준비한다 — 어느 쪽으로 조회했는지가 이 테스트의 관심사가
    // 아니라(그건 PortalLabelingDataPath 가 고정한다) 이동 경로만 본다.
    mock.onGet('/portal/frames/777/labels').reply(200, labelsPayload(777));
    mock.onGet('/portal/frames/778/labels').reply(200, labelsPayload(778));
    mock.onGet('/frames/777/labels').reply(200, labelsPayload(777));
    mock.onGet('/frames/778/labels').reply(200, labelsPayload(778));
    mock.onGet(/\/(portal\/)?frames\/\d+\/image/).reply(200, new Blob([new Uint8Array([1])]));
    mock.onGet(/\/(portal\/)?frames\/\d+\/meta/).reply(200, {
      success: true,
      data: { srcSn: 777, frameNo: 1, imageUrl: '', imageWidth: 0, imageHeight: 0 },
      message: null,
      errorCode: null,
    });
    mock.onGet(/\/videos\/\d+$/).reply(200, {
      success: true,
      data: { rawSn: 7, deIdntfYn: 'Y' },
      message: null,
      errorCode: null,
    });
    mock.onAny().reply(200, { success: true, data: null, message: null, errorCode: null });
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
    vi.clearAllMocks();
  });

  async function renderAndJumpToSecondFrame(entry: string) {
    renderWithProviders(<LabelingPage />, {
      initialEntries: [entry],
      routes: [
        { path: '/label/:id', element: <LabelingPage /> },
        { path: '/portal/label/:id', element: <LabelingPage /> },
      ],
    });
    // 썸네일 strip 이 실제로 렌더될 때까지 대기(로딩 화면도 labeling-page testid 를 갖는다).
    // ⚠ getByRole+name 은 이 화면의 큰 DOM 에서 접근성 트리를 매번 계산해 기본 1초 안에 못
    //   끝내는 경우가 있다. aria-label 직접 조회로 바꿔 대기 시간을 안정화한다.
    const target = await screen.findByLabelText('프레임 2');
    // 미저장 변경이 없으므로 이동 가드 모달 없이 곧바로 이동한다.
    fireEvent.click(target);
  }

  it('포털_채널에서_프레임을_이동하면_포털_경로로_간다', async () => {
    useAuthStore.setState({
      token: 'tok',
      claims: { sub: 'u-99', role: 'PORTAL_USER', channel: 'PORTAL', exp: 9999999999 },
    });

    await renderAndJumpToSecondFrame('/portal/label/777');

    await waitFor(() =>
      expect(navigateSpy).toHaveBeenCalledWith('/portal/label/778', { replace: true }),
    );
    // 내부 경로로는 절대 이동하지 않는다 — 그 라우트는 INTERNAL 채널 가드가 막아
    // 접근 거부 화면이 뜬다(이 결함의 실제 증상).
    expect(navigateSpy).not.toHaveBeenCalledWith('/label/778', expect.anything());
  });

  it('내부_채널에서_프레임을_이동하면_기존대로_내부_경로로_간다', async () => {
    useAuthStore.setState({
      token: 'tok',
      claims: { sub: 'u-1', role: 'WORKER', channel: 'INTERNAL', exp: 9999999999 },
    });

    await renderAndJumpToSecondFrame('/label/777');

    await waitFor(() => expect(navigateSpy).toHaveBeenCalledWith('/label/778', { replace: true }));
    expect(navigateSpy).not.toHaveBeenCalledWith('/portal/label/778', expect.anything());
  });
});
