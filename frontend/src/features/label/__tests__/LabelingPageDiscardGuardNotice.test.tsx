// R4·R5 — 미저장 가드(프레임 이동 · 닫기)에 실리는 「폐기 프레임」 안내.
//
// 확정 사양(SCREEN-005): "저장에 폐기 상태 변경이 들어 있으면 몇 개 프레임이 학습데이터에서 빠지고
// 몇 개가 되돌아오는지 알리고 확인을 받는다." 그 사양은 <b>저장 일반</b>을 가리키는데, 저장 축에는
// 헤더 저장 버튼 말고도 **프레임 이동 가드의 "저장 후 이동"** 과 **닫기 가드의 "저장 후 닫기"** 가
// 있다(둘 다 persistPendingWork 를 직접 탄다). 그 두 다이얼로그는 "저장하고 이동/닫기"만 물을 뿐
// 프레임이 산출물에서 빠진다는 사실은 말하지 않았다.
//
// ⚠ 확인 모달을 그 위에 겹치지 않는다 — 포커스 트랩이 중첩되고 시안에도 그런 연쇄가 없다. 대신
//   기존 가드 다이얼로그 본문에 안내를 <b>인라인으로</b> 넣는다.
//
// ⚠ 폐기 변경이 <b>없으면</b> 기존 가드가 그대로여야 한다 — 그 음성 가드가 이 파일에서 제일 중요하다.

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { fireEvent, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

vi.mock('react-konva', async () => (await import('@/test/konvaMock')).createKonvaMock());

import { apiClient } from '@/lib/api/client';
import { LabelingPage } from '@/pages/label/LabelingPage';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';
import { useLabelStore } from '@/stores/useLabelStore';

const SRC_SN = 200;
const NEXT_SRC_SN = 201;

const labelOnFrame0 = {
  id: 'lbl-1',
  frameNo: 0,
  classId: 1,
  className: 'car',
  source: 'MANUAL',
  shape: { type: 'BBOX', left: 0, top: 0, right: 50, bottom: 30 },
};

function labelsPayload(
  srcSn: number,
  frameNo: number,
  labels: unknown[],
  dscdYn: 'Y' | 'N',
) {
  return {
    success: true,
    data: {
      frameNo,
      srcSn,
      videoId: 7,
      labelVersion: 3,
      dscdYn,
      siblings: [
        { srcSn: SRC_SN, frameNo: 0, hasLabel: true, dscdYn },
        { srcSn: NEXT_SRC_SN, frameNo: 1, hasLabel: false, dscdYn: 'N' },
      ],
      labels,
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

/** D 단축키 = 다음 프레임 이동 요청(가드 경유). */
function pressNextFrame() {
  fireEvent.keyDown(window, { key: 'd', code: 'KeyD' });
}

describe('LabelingPage — 가드 다이얼로그의 폐기 안내', () => {
  let mock: MockAdapter;
  /** 저장 PUT 이 실제로 실어 보낸 바디. null 이면 아직 요청이 나가지 않았다는 뜻이다. */
  let sentSave: Record<string, unknown> | null;

  function setup(serverDscdYn: 'Y' | 'N' = 'N') {
    sentSave = null;
    mock = new MockAdapter(apiClient);
    useAuthStore.setState({
      claims: { sub: '10', role: 'WORKER', channel: 'INTERNAL', exp: 9999999999 },
    });
    useLabelStore.getState().reset();
    mock
      .onGet(`/frames/${SRC_SN}/labels`)
      .reply(200, labelsPayload(SRC_SN, 0, [labelOnFrame0], serverDscdYn));
    mock
      .onGet(`/frames/${NEXT_SRC_SN}/labels`)
      .reply(200, labelsPayload(NEXT_SRC_SN, 1, [], 'N'));
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
    mock
      .onGet(/\/videos\/\d+$/)
      .reply(200, { success: true, data: null, message: null, errorCode: null });
    mock
      .onGet(/\/reviews\/\d+$/)
      .reply(200, { success: true, data: null, message: null, errorCode: null });
    // ⚠ 저장 PUT 은 catch-all 보다 먼저 등록한다(등록 순서대로 매칭).
    mock.onPut(`/frames/${SRC_SN}/labels`).reply((config) => {
      sentSave = JSON.parse(config.data as string) as Record<string, unknown>;
      return [
        200,
        labelsPayload(SRC_SN, 0, [labelOnFrame0], (sentSave.dscdYn as 'Y' | 'N') ?? serverDscdYn),
      ];
    });
    mock.onAny().reply(200, { success: true, data: null, message: null, errorCode: null });
  }

  /** 초기 프레임 로드 완료를 기다린다. */
  async function loaded() {
    await waitFor(() => {
      expect(useLabelStore.getState().labels).toHaveLength(1);
    });
  }

  /** 라벨만 고친다(폐기 전환 없음) — 음성 가드의 전제. */
  async function dirtyLabelOnly() {
    useLabelStore.getState().updateLabel('lbl-1', { className: 'bus' });
    await waitFor(() => {
      expect(useLabelStore.getState().dirtyLabels.size).toBeGreaterThan(0);
    });
  }

  beforeEach(() => setup('N'));

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
    useLabelStore.getState().reset();
  });

  it('폐기_전환이_있으면_프레임_이동_가드에_안내가_함께_뜬다', async () => {
    // given
    renderPage();
    const user = userEvent.setup();
    await loaded();
    await user.click(screen.getByTestId('frame-discard-toggle'));

    // when
    pressNextFrame();

    // then: 기존 3옵션은 그대로이고 안내가 더해진다.
    expect(await screen.findByTestId('frame-nav-guard-save')).toBeInTheDocument();
    const notice = screen.getByTestId('frame-nav-guard-discard-notice');
    expect(notice).toHaveTextContent('1개 프레임이 학습데이터에서 빠집니다.');
    // 지우는 것이 아니라는 사실을 함께 말한다 — 사용자 오해를 직접 막는 문장이라 줄이지 않는다.
    expect(notice).toHaveTextContent(
      '프레임·이미지·라벨은 지우지 않습니다. 산출물과 데이터마트 노출에서만 빠집니다.',
    );
  });

  it('폐기_전환이_있으면_닫기_가드에_안내가_함께_뜬다', async () => {
    // given
    renderPage();
    const user = userEvent.setup();
    await loaded();
    await user.click(screen.getByTestId('frame-discard-toggle'));

    // when
    await user.click(screen.getByLabelText('뒤로가기'));

    // then
    expect(await screen.findByTestId('label-close-save')).toBeInTheDocument();
    const notice = screen.getByTestId('label-close-discard-notice');
    expect(notice).toHaveTextContent('1개 프레임이 학습데이터에서 빠집니다.');
    expect(notice).toHaveTextContent(
      '프레임·이미지·라벨은 지우지 않습니다. 산출물과 데이터마트 노출에서만 빠집니다.',
    );
  });

  it('폐기_변경이_없으면_프레임_이동_가드에_안내가_없다', async () => {
    // given: 라벨만 고친 미저장 — 모든 가드에 폐기 안내를 끼우면 사실이 아닌 안내가 된다.
    renderPage();
    await loaded();
    await dirtyLabelOnly();

    // when
    pressNextFrame();

    // then
    expect(await screen.findByTestId('frame-nav-guard-save')).toBeInTheDocument();
    expect(screen.queryByTestId('frame-nav-guard-discard-notice')).toBeNull();
  });

  it('폐기_변경이_없으면_닫기_가드에_안내가_없다', async () => {
    // given
    renderPage();
    const user = userEvent.setup();
    await loaded();
    await dirtyLabelOnly();

    // when
    await user.click(screen.getByLabelText('뒤로가기'));

    // then
    expect(await screen.findByTestId('label-close-save')).toBeInTheDocument();
    expect(screen.queryByTestId('label-close-discard-notice')).toBeNull();
  });

  it('폐기했다_되돌리면_가드에_안내가_다시_사라진다', async () => {
    // given: 서버값으로 돌아왔으면 이 저장에는 폐기 변경이 없다.
    renderPage();
    const user = userEvent.setup();
    await loaded();
    await dirtyLabelOnly();
    await user.click(screen.getByTestId('frame-discard-toggle'));
    await user.click(screen.getByTestId('frame-discard-toggle'));

    // when
    pressNextFrame();

    // then
    expect(await screen.findByTestId('frame-nav-guard-save')).toBeInTheDocument();
    expect(screen.queryByTestId('frame-nav-guard-discard-notice')).toBeNull();
  });

  it('안내는_되돌아오는_프레임_수도_말한다', async () => {
    // given: 서버값이 폐기(Y)인 프레임을 복원한다.
    mock.restore();
    setup('Y');
    renderPage();
    const user = userEvent.setup();
    await loaded();
    await user.click(screen.getByTestId('frame-discard-toggle'));

    // when
    pressNextFrame();

    // then
    const notice = await screen.findByTestId('frame-nav-guard-discard-notice');
    expect(notice).toHaveTextContent('1개 프레임이 학습데이터로 되돌아옵니다.');
  });

  it('안내가_떠도_프레임_이동_가드의_3옵션은_그대로_동작한다', async () => {
    // given: 저장 후 이동 — 폐기 전환이 실린 채 저장되고 다음 프레임으로 넘어간다.
    renderPage();
    const user = userEvent.setup();
    await loaded();
    await user.click(screen.getByTestId('frame-discard-toggle'));
    pressNextFrame();
    await screen.findByTestId('frame-nav-guard-discard-notice');
    // 3옵션이 모두 살아 있다.
    expect(screen.getByTestId('frame-nav-guard-cancel')).toBeInTheDocument();
    expect(screen.getByTestId('frame-nav-guard-discard')).toBeInTheDocument();

    // when
    await user.click(screen.getByTestId('frame-nav-guard-save'));

    // then
    await waitFor(() => expect(sentSave).not.toBeNull());
    expect(sentSave!.dscdYn).toBe('Y');
    await waitFor(() => expect(useLabelStore.getState().labels).toHaveLength(0));
  });

  it('안내가_떠도_프레임_이동_가드의_취소는_현재_프레임을_유지한다', async () => {
    // given
    renderPage();
    const user = userEvent.setup();
    await loaded();
    await user.click(screen.getByTestId('frame-discard-toggle'));
    pressNextFrame();
    await screen.findByTestId('frame-nav-guard-discard-notice');

    // when
    await user.click(screen.getByTestId('frame-nav-guard-cancel'));

    // then: 저장하지 않고 현재 프레임에 남는다(폐기 전환도 보존).
    await waitFor(() => expect(screen.queryByTestId('frame-nav-guard-save')).toBeNull());
    expect(sentSave).toBeNull();
    expect(useLabelStore.getState().labels).toHaveLength(1);
    expect(screen.getByTestId('frame-discard-pending')).toBeInTheDocument();
  });

  it('안내가_떠도_닫기_가드의_3옵션은_그대로_동작한다', async () => {
    // given
    renderPage();
    const user = userEvent.setup();
    await loaded();
    await user.click(screen.getByTestId('frame-discard-toggle'));
    await user.click(screen.getByLabelText('뒤로가기'));
    await screen.findByTestId('label-close-discard-notice');
    expect(screen.getByTestId('label-close-cancel')).toBeInTheDocument();
    expect(screen.getByTestId('label-close-discard')).toBeInTheDocument();

    // when
    await user.click(screen.getByTestId('label-close-save'));

    // then
    await waitFor(() => expect(sentSave).not.toBeNull());
    expect(sentSave!.dscdYn).toBe('Y');
  });

  it('안내가_떠도_닫기_가드의_취소는_저장하지_않고_머무른다', async () => {
    // given
    renderPage();
    const user = userEvent.setup();
    await loaded();
    await user.click(screen.getByTestId('frame-discard-toggle'));
    await user.click(screen.getByLabelText('뒤로가기'));
    await screen.findByTestId('label-close-discard-notice');

    // when
    await user.click(screen.getByTestId('label-close-cancel'));

    // then
    await waitFor(() => expect(screen.queryByTestId('label-close-save')).toBeNull());
    expect(sentSave).toBeNull();
    expect(screen.getByTestId('frame-discard-pending')).toBeInTheDocument();
  });
});
