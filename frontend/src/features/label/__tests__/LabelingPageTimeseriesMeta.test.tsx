// LabelingPage — 메타 탭 요약 카드 → 「영상 분석 설명 · 이벤트 어노테이션」 창 열기.
//
// ⚠ 2026-09-14 — 구 시험은 「메타 탭에 시계열 메타 토글 버튼이 있다」를 고정했다. 그 패널이
//   창의 칸으로 옮겨갔으므로(전제 변경, 회귀 아님) 이제 고정하는 것은 두 가지다:
//   ① 메타 탭에는 요약 카드만 있고 전문 입력 칸이 없다
//   ② 그 카드의 버튼을 누르면 창이 뜨고 그 안에 영상 분석 설명 값이 보인다
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

vi.mock('react-konva', async () => (await import('@/test/konvaMock')).createKonvaMock());

import { apiClient } from '@/lib/api/client';
import { LabelingPage } from '@/pages/label/LabelingPage';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';

function labelsPayload(srcSn: number) {
  return {
    success: true,
    data: {
      frameNo: 0,
      srcSn,
      videoId: 7,
      siblings: [{ srcSn, frameNo: 0 }],
      labels: [],
    },
    message: null,
    errorCode: null,
  };
}

function metaPayload(srcSn: number) {
  return {
    success: true,
    data: {
      srcSn,
      frameNo: 0,
      imageUrl: '',
      imageWidth: 0,
      imageHeight: 0,
      items: [{ metaSn: 1, metaKey: 'vlm.description', metaVal: '도로에서 차량이 충돌했다' }],
      technicalMeta: [],
      readOnlyMeta: [],
      stateChanges: [],
    },
    message: null,
    errorCode: null,
  };
}

describe('LabelingPage 영상 분석 설명 — 요약 카드와 창', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    useAuthStore.setState({
      token: ['t', 'o', 'k'].join(''),
      claims: { sub: '10', role: 'WORKER', channel: 'INTERNAL', exp: 9999999999 },
    });
    mock.onGet('/frames/300/labels').reply(200, labelsPayload(300));
    mock.onGet('/frames/300/image').reply(200, new Blob());
    mock.onGet(/\/frames\/\d+\/meta/).reply(200, metaPayload(300));
    mock.onGet(/\/videos\/\d+\/event-annotation/).reply(404, {
      success: false,
      data: null,
      message: '없음',
      errorCode: 'NOT_FOUND',
    });
    mock.onAny().reply(200, { success: true, data: null, message: null, errorCode: null });
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  async function openMetaTab() {
    const user = userEvent.setup();
    renderWithProviders(<LabelingPage />, {
      initialEntries: ['/label/300'],
      routes: [{ path: '/label/:id', element: <LabelingPage /> }],
    });
    await waitFor(() => expect(screen.getByTestId('labeling-page')).toBeInTheDocument());
    await waitFor(() => expect(screen.getByTestId('right-tab-meta')).toBeInTheDocument());
    await user.click(screen.getByTestId('right-tab-meta'));
    return user;
  }

  it('메타탭에는_요약카드만_있고_전문_입력칸은_없다', async () => {
    await openMetaTab();

    const card = await screen.findByTestId('annotation-summary-card');
    // 요약은 첫 줄만 보인다.
    await waitFor(() =>
      expect(screen.getByTestId('annotation-summary-description')).toHaveTextContent(
        '도로에서 차량이 충돌했다',
      ),
    );
    expect(within(card).queryByRole('textbox')).not.toBeInTheDocument();
    expect(screen.queryByTestId('annotation-window')).not.toBeInTheDocument();
  });

  it('★요약카드_버튼으로_창을_열면_영상_분석_설명_값이_보인다', async () => {
    const user = await openMetaTab();

    const openButton = await screen.findByTestId('annotation-summary-open');
    expect(openButton).toHaveTextContent('크게 보기 · 작성');
    await user.click(openButton);

    const window = await screen.findByTestId('annotation-window');
    expect(window).toHaveAttribute('aria-label', '영상 분석 설명 · 이벤트 어노테이션');
    await waitFor(() =>
      expect(screen.getByLabelText('영상 분석 설명 입력')).toHaveValue(
        '도로에서 차량이 충돌했다',
      ),
    );
    // 창이 열려 있으면 카드 버튼은 「창 앞으로 가져오기」가 된다(창은 동시에 하나다).
    expect(screen.getByTestId('annotation-summary-open')).toHaveTextContent('창 앞으로 가져오기');
    expect(screen.getByTestId('annotation-summary-state')).toHaveTextContent('창 열림');
  });

  it('★창_안_입력에_포커스가_있으면_뒤_화면_단축키가_동작하지_않는다', async () => {
    // given — 창을 열고 영상 분석 설명 칸에 커서를 둔다.
    const user = await openMetaTab();
    await user.click(await screen.findByTestId('annotation-summary-open'));
    await screen.findByTestId('annotation-window');

    // 양성 대조군 — 입력 밖에서 누른 '?' 는 단축키 도움말을 연다(단축키가 살아 있다).
    await user.click(screen.getByTestId('right-tab-meta'));
    await user.keyboard('?');
    const cheatSheet = await screen.findByRole('dialog', { name: '단축키 도움말' });
    await user.keyboard('{Escape}');
    await waitFor(() => expect(cheatSheet).not.toBeInTheDocument());

    // when — 창 안 입력에 포커스를 두고 같은 키를 친다.
    const input = screen.getByLabelText('영상 분석 설명 입력');
    await user.click(input);
    await user.keyboard('?');

    // then — 도움말이 열리지 않고 그 글자는 입력에 들어간다(글을 쓰다 단축키가 튀지 않는다).
    expect(screen.queryByRole('dialog', { name: '단축키 도움말' })).toBeNull();
    expect(input).toHaveValue('도로에서 차량이 충돌했다?');
  });

  it('★창이_떠_있어도_뒤_화면의_우측_탭을_조작할_수_있다_비모달', async () => {
    // given — 백드롭을 두지 않고 포커스를 가두지 않는 것이 이 창의 계약이다.
    const user = await openMetaTab();
    await user.click(await screen.findByTestId('annotation-summary-open'));
    await screen.findByTestId('annotation-window');

    // then — 백드롭이 없다(모달이 아니다).
    expect(screen.queryByTestId('modal-backdrop')).not.toBeInTheDocument();
    expect(screen.getByTestId('annotation-window')).toHaveAttribute('aria-modal', 'false');

    // when — 뒤 화면의 탭을 그대로 누른다.
    await user.click(screen.getByTestId('right-tab-objects'));

    // then — 탭이 바뀌고 창은 그대로 떠 있다.
    expect(screen.getByText('객체 목록')).toBeInTheDocument();
    expect(screen.getByTestId('annotation-window')).toBeInTheDocument();
  });
});
