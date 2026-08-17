// R4·R5 — 「폐기 프레임 저장 확인」 모달.
//
// 확정 사양(SCREEN-005): "저장에 폐기 상태 변경이 들어 있으면 몇 개 프레임이 학습데이터에서 빠지고
// 몇 개가 되돌아오는지 알리고 확인을 받는다. 폐기는 되돌릴 수 있지만 산출물에서 빠지는 결정이라
// 저장 전에 한 번 드러낸다."
//
// ⚠ 폐기 변경이 <b>없는</b> 저장에는 끼어들지 않는다 — 모든 저장에 확인을 끼우면 작업 흐름이
//   망가진다. 그 음성 가드가 이 파일에서 제일 중요한 케이스다.

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

vi.mock('react-konva', async () => (await import('@/test/konvaMock')).createKonvaMock());

import { apiClient } from '@/lib/api/client';
import { LabelingPage } from '@/pages/label/LabelingPage';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';

const SRC_SN = 300;
const VIDEO_ID = 7;

function labelsPayload(dscdYn: 'Y' | 'N') {
  return {
    success: true,
    data: {
      frameNo: 0,
      srcSn: SRC_SN,
      videoId: VIDEO_ID,
      labelVersion: 3,
      dscdYn,
      siblings: [
        { srcSn: SRC_SN, frameNo: 0, hasLabel: false, dscdYn },
        { srcSn: 301, frameNo: 1, hasLabel: false, dscdYn: 'N' },
      ],
      labels: [],
    },
    message: null,
    errorCode: null,
  };
}

function renderPage() {
  return renderWithProviders(<LabelingPage />, {
    initialEntries: [`/label/${SRC_SN}`],
    routes: [{ path: '/label/:id', element: <LabelingPage /> }],
  });
}

describe('LabelingPage — 폐기 프레임 저장 확인', () => {
  let mock: MockAdapter;
  /** 저장 PUT 이 실제로 실어 보낸 바디. null 이면 아직 요청이 나가지 않았다는 뜻이다. */
  let sentSave: Record<string, unknown> | null;

  function setup(serverDscdYn: 'Y' | 'N' = 'N') {
    sentSave = null;
    mock = new MockAdapter(apiClient);
    useAuthStore.setState({
      claims: { sub: '10', role: 'WORKER', channel: 'INTERNAL', exp: 9999999999 },
    });
    mock.onGet(`/frames/${SRC_SN}/labels`).reply(200, labelsPayload(serverDscdYn));
    mock.onGet(/\/frames\/\d+\/image/).reply(200, new Blob());
    mock
      .onGet(/\/frames\/\d+\/versions/)
      .reply(200, { success: true, data: [], message: null, errorCode: null });
    mock
      .onGet(/\/videos\/\d+\/versions/)
      .reply(200, { success: true, data: [], message: null, errorCode: null });
    mock.onGet(/\/frames\/\d+\/label-history/).reply(200, {
      success: true,
      data: { content: [], number: 0, size: 20, totalElements: 0, totalPages: 0 },
      message: null,
      errorCode: null,
    });
    mock.onGet(/\/videos\/\d+$/).reply(200, { success: true, data: null, message: null, errorCode: null });
    mock.onGet(/\/reviews\/\d+$/).reply(200, { success: true, data: null, message: null, errorCode: null });
    // ⚠ 저장 PUT 은 catch-all 보다 먼저 등록한다(등록 순서대로 매칭).
    mock.onPut(`/frames/${SRC_SN}/labels`).reply((config) => {
      sentSave = JSON.parse(config.data as string) as Record<string, unknown>;
      return [200, labelsPayload((sentSave.dscdYn as 'Y' | 'N') ?? serverDscdYn)];
    });
    mock.onAny().reply(200, { success: true, data: null, message: null, errorCode: null });
  }

  beforeEach(() => setup('N'));

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  it('폐기_전환이_실린_저장은_확인_모달을_거치며_그_시점에_서버로_나가지_않는다', async () => {
    // given
    renderPage();
    const user = userEvent.setup();
    await user.click(await screen.findByTestId('frame-discard-toggle'));

    // when
    await user.click(screen.getByTestId('label-toolbar-save'));

    // then: 모달만 뜨고 저장은 아직 나가지 않았다.
    expect(await screen.findByTestId('discard-save-confirm')).toBeInTheDocument();
    expect(sentSave).toBeNull();
  });

  it('확인하고_저장을_누르면_저장이_실제로_나간다', async () => {
    // given
    renderPage();
    const user = userEvent.setup();
    await user.click(await screen.findByTestId('frame-discard-toggle'));
    await user.click(screen.getByTestId('label-toolbar-save'));
    await screen.findByTestId('discard-save-confirm');

    // when
    await user.click(screen.getByTestId('discard-save-confirm-submit'));

    // then
    await waitFor(() => expect(sentSave).not.toBeNull());
    expect(sentSave!.dscdYn).toBe('Y');
  });

  it('취소하면_저장하지_않고_폐기_전환이_그대로_남는다', async () => {
    // given: 사용자의 편집 상태를 동의 없이 버리지 않는다.
    renderPage();
    const user = userEvent.setup();
    await user.click(await screen.findByTestId('frame-discard-toggle'));
    await user.click(screen.getByTestId('label-toolbar-save'));
    await screen.findByTestId('discard-save-confirm');

    // when
    await user.click(screen.getByTestId('discard-save-confirm-cancel'));

    // then
    await waitFor(() => expect(screen.queryByTestId('discard-save-confirm')).toBeNull());
    expect(sentSave).toBeNull();
    expect(screen.getByTestId('frame-discard-pending')).toBeInTheDocument();
  });

  it('폐기_변경이_없는_저장은_모달_없이_바로_나간다', async () => {
    // given: 모든 저장에 확인을 끼우면 작업 흐름이 망가진다(음성 가드).
    renderPage();
    const user = userEvent.setup();

    // when
    await user.click(await screen.findByTestId('label-toolbar-save'));

    // then
    await waitFor(() => expect(sentSave).not.toBeNull());
    expect(screen.queryByTestId('discard-save-confirm')).toBeNull();
  });

  it('폐기했다_되돌린_저장도_모달_없이_바로_나간다', async () => {
    // given: 서버값으로 되돌아왔으면 이 저장에는 폐기 변경이 없다.
    renderPage();
    const user = userEvent.setup();
    await user.click(await screen.findByTestId('frame-discard-toggle'));
    await user.click(screen.getByTestId('frame-discard-toggle'));

    // when
    await user.click(screen.getByTestId('label-toolbar-save'));

    // then
    await waitFor(() => expect(sentSave).not.toBeNull());
    expect(screen.queryByTestId('discard-save-confirm')).toBeNull();
  });

  it('문구는_빠지는_프레임_수를_말한다', async () => {
    // given
    renderPage();
    const user = userEvent.setup();
    await user.click(await screen.findByTestId('frame-discard-toggle'));

    // when
    await user.click(screen.getByTestId('label-toolbar-save'));

    // then
    const body = await screen.findByTestId('discard-save-confirm-summary');
    expect(body).toHaveTextContent('1개 프레임이 학습데이터에서 빠집니다.');
    // 지우는 것이 아니라는 사실을 함께 말한다 — 사용자 오해를 직접 막는 문장이다.
    expect(screen.getByTestId('discard-save-confirm')).toHaveTextContent(
      '프레임·이미지·라벨은 지우지 않습니다. 산출물과 데이터마트 노출에서만 빠집니다.',
    );
  });

  it('문구는_되돌아오는_프레임_수를_말한다', async () => {
    // given: 서버값이 폐기(Y)인 프레임을 복원한다.
    mock.restore();
    setup('Y');
    renderPage();
    const user = userEvent.setup();
    await user.click(await screen.findByTestId('frame-discard-toggle'));

    // when
    await user.click(screen.getByTestId('label-toolbar-save'));

    // then
    const body = await screen.findByTestId('discard-save-confirm-summary');
    expect(body).toHaveTextContent('1개 프레임이 학습데이터로 되돌아옵니다.');
  });

  it('확인_모달의_제목과_버튼은_시안_문구를_따른다', async () => {
    // given
    renderPage();
    const user = userEvent.setup();
    await user.click(await screen.findByTestId('frame-discard-toggle'));

    // when
    await user.click(screen.getByTestId('label-toolbar-save'));

    // then
    await screen.findByTestId('discard-save-confirm');
    expect(within(screen.getByRole('dialog')).getByText('폐기 프레임 저장 확인')).toBeInTheDocument();
    expect(screen.getByTestId('discard-save-confirm-submit')).toHaveTextContent('확인하고 저장');
    expect(screen.getByTestId('discard-save-confirm-cancel')).toHaveTextContent('취소');
  });
});
