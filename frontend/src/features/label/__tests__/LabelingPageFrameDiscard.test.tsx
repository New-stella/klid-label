// R4·R5 — 라벨링 화면의 프레임 폐기·복원 배선.
//
// 확정 사양: "누르면 화면 표시만 바뀌고 저장을 눌러야 확정된다"(D8) · "폐기한 프레임은 읽기 전용이
// 되어 복원하기 전까지 라벨을 고칠 수 없다" · "저작도구 화면의 프레임 수에서는 빼지 않는다"(D2).

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
        { srcSn: 301, frameNo: 1, hasLabel: false, dscdYn: 'Y' },
        { srcSn: 302, frameNo: 2, hasLabel: false, dscdYn: 'N' },
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

describe('LabelingPage — 프레임 폐기·복원', () => {
  let mock: MockAdapter;
  /** 저장 PUT 이 실제로 실어 보낸 바디. null 이면 아직 요청이 나가지 않았다는 뜻이다. */
  let sentSave: Record<string, unknown> | null;

  beforeEach(() => {
    sentSave = null;
    mock = new MockAdapter(apiClient);
    useAuthStore.setState({
      claims: { sub: '10', role: 'WORKER', channel: 'INTERNAL', exp: 9999999999 },
    });
    mock.onGet(`/frames/${SRC_SN}/labels`).reply(200, labelsPayload('N'));
    mock.onGet(/\/frames\/\d+\/image/).reply(200, new Blob());
    mock.onGet(/\/frames\/\d+\/versions/).reply(200, { success: true, data: [], message: null, errorCode: null });
    mock.onGet(/\/videos\/\d+\/versions/).reply(200, { success: true, data: [], message: null, errorCode: null });
    mock.onGet(/\/frames\/\d+\/label-history/).reply(200, {
      success: true,
      data: { content: [], number: 0, size: 20, totalElements: 0, totalPages: 0 },
      message: null,
      errorCode: null,
    });
    mock.onGet(/\/videos\/\d+$/).reply(200, { success: true, data: null, message: null, errorCode: null });
    mock.onGet(/\/reviews\/\d+$/).reply(200, { success: true, data: null, message: null, errorCode: null });
    // ⚠ 저장 PUT 은 **catch-all 보다 먼저** 등록해야 한다 — axios-mock-adapter 는 등록 순서대로
    //   매칭하므로 뒤에 두면 catch-all 이 먼저 삼켜 요청 바디를 관측할 수 없다.
    mock.onPut(`/frames/${SRC_SN}/labels`).reply((config) => {
      sentSave = JSON.parse(config.data as string) as Record<string, unknown>;
      return [200, labelsPayload((sentSave.dscdYn as 'Y' | 'N') ?? 'N')];
    });
    mock.onAny().reply(200, { success: true, data: null, message: null, errorCode: null });
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  it('폐기_전환은_저장을_눌러야_서버로_나간다', async () => {
    // given
    renderPage();
    const user = userEvent.setup();

    // when: 폐기 전환만 하면 아직 나가지 않는다
    await user.click(await screen.findByTestId('frame-discard-toggle'));
    expect(sentSave).toBeNull();
    expect(screen.getByTestId('frame-discard-pending')).toBeInTheDocument();

    // and: 저장해야 확정된다 — 폐기 변경이 실렸으므로 확인을 한 번 거친다
    //   (「폐기 프레임 저장 확인」 — LabelingPageDiscardSaveConfirm.test.tsx 가 그 계약을 고정한다).
    await user.click(screen.getByTestId('label-toolbar-save'));
    await user.click(await screen.findByTestId('discard-save-confirm-submit'));

    // then
    await waitFor(() => expect(sentSave).not.toBeNull());
    expect(sentSave!.dscdYn).toBe('Y');
  });

  it('폐기를_건드리지_않은_저장은_폐기여부를_보내지_않는다', async () => {
    // given: 보내면 "현재 값 유지" 규약이 깨져 남의 폐기 결정을 조용히 덮는다.
    renderPage();
    const user = userEvent.setup();

    // when
    await user.click(await screen.findByTestId('label-toolbar-save'));

    // then
    await waitFor(() => expect(sentSave).not.toBeNull());
    expect('dscdYn' in sentSave!).toBe(false);
  });

  it('폐기했다_되돌리면_미저장_표식이_사라진다', async () => {
    // given
    renderPage();
    const user = userEvent.setup();
    const toggle = await screen.findByTestId('frame-discard-toggle');

    // when: 폐기 → 복원(서버값과 같아짐)
    await user.click(toggle);
    expect(screen.getByTestId('frame-discard-pending')).toBeInTheDocument();
    await user.click(screen.getByTestId('frame-discard-toggle'));

    // then
    expect(screen.queryByTestId('frame-discard-pending')).toBeNull();
  });

  it('폐기된_프레임은_읽기_전용이_되고_그_사실을_캔버스에_알린다', async () => {
    // given: D7 — BE 는 폐기 프레임 저장을 막지 않는다. 편집 차단은 화면이 책임진다.
    mock.onGet(`/frames/${SRC_SN}/labels`).reply(200, labelsPayload('Y'));

    // when
    renderPage();

    // then
    expect(await screen.findByTestId('frame-discarded-notice')).toBeInTheDocument();
    await waitFor(() =>
      expect(screen.getByTestId('canvas-shell')).toHaveAttribute('data-read-only', 'true'),
    );
  });

  it('폐기된_프레임에서도_복원과_저장은_열려_있다', async () => {
    // given: 둘 다 막으면 폐기를 되돌릴 방법이 없다("차단엔 되돌리는 길").
    mock.onGet(`/frames/${SRC_SN}/labels`).reply(200, labelsPayload('Y'));

    // when
    renderPage();

    // then
    expect(await screen.findByTestId('frame-discard-toggle')).toBeEnabled();
    expect(screen.getByTestId('label-toolbar-save')).toBeEnabled();
  });

  it('폐기된_형제_프레임도_썸네일_띠에서_빠지지_않고_표식만_붙는다', async () => {
    // given/when: D2 — 내부 화면의 프레임 총량은 폐기분을 뺀 값이 아니다.
    renderPage();

    // then
    const thumbs = await screen.findAllByRole('option');
    expect(thumbs).toHaveLength(3);
    expect(within(thumbs[1]).getByText('폐기')).toBeInTheDocument();
    expect(within(thumbs[2]).queryByText('폐기')).toBeNull();
  });
});
