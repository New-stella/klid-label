// SCREEN-039 「경로 선택」 — 찾아보기로 위치를 골라 입력칸에 넣는 흐름의 회귀 가드.
//
// 못 박는 것 (AC-120):
//   · 위치를 지정하지 않으면 허용 저장소 루트 목록으로 시작한다.
//   · 폴더는 눌러 한 단계 내려가고, 「상위로」의 활성 여부는 응답의 `parent` 하나가 정한다.
//   · 고른 값은 사용자가 누른 표기가 아니라 **서버가 돌려준 실제 위치(`path`)** 다.
//   · 나눠 받은 쪽들을 이어붙인다 — 그 축의 가드는 `ImportPathPickerCursor.test.tsx` 가 맡는다.
//   · 조회가 거부돼도 창을 닫지 않고 그 자리에서 서버 메시지를 보여주며, 입력칸은 그대로 열려
//     있어 경로를 직접 적어 검사할 수 있다 — 고르는 길이 적는 길을 대신하지 않는다.
//
// @design SCREEN-039 API-221 API-222 AC-120

import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { apiClient } from '@/lib/api/client';
import { ImportPage } from '@/pages/manage/ImportPage';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';

const EMPTY_HISTORY = {
  success: true,
  data: { content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 },
  message: null,
  errorCode: null,
};
const EMPTY_MAPPINGS = {
  success: true,
  data: { items: [], page: 0, size: 20, totalElements: 0, totalPages: 0 },
  message: null,
  errorCode: null,
};

function browseBody(data: {
  path?: string | null;
  parent?: string | null;
  entries?: { name: string; path: string }[];
  nextCursor?: string | null;
}) {
  return {
    success: true,
    data: {
      path: null,
      parent: null,
      entries: [],
      // 기본은 「끝까지 봤다」 — 이 파일의 케이스들은 이어받기 축이 아니라 이동·선택 축을 본다.
      nextCursor: null,
      ...data,
    },
    message: null,
    errorCode: null,
  };
}

describe('SCREEN-039 경로 선택 — 찾아보기', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    mock.onGet('/imports').reply(200, EMPTY_HISTORY);
    mock.onGet('/import-mappings').reply(200, EMPTY_MAPPINGS);
    useAuthStore.setState({
      token: 'tok',
      claims: { sub: '1', role: 'REVIEWER', channel: 'INTERNAL', exp: 9999999999 },
    });
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  it('루트에서_시작해_폴더를_내려가_고른_값이_입력칸에_들어간다', async () => {
    const user = userEvent.setup();
    // 루트 목록 — path·parent 가 없다(기준 위치가 하나로 정해지지 않는다).
    mock
      .onGet('/imports/folders', { params: {} })
      .reply(200, browseBody({ entries: [{ name: 'handover', path: '/nas-storage/handover' }] }));
    // 한 단계 아래 — ★서버가 돌려주는 `path` 는 요청 표기가 아니라 실제 위치다.
    mock.onGet('/imports/folders', { params: { path: '/nas-storage/handover' } }).reply(
      200,
      browseBody({
        path: '/nas-storage/handover/real',
        parent: null,
        entries: [],
      }),
    );

    renderWithProviders(<ImportPage />);
    await user.click(screen.getByTestId('import-folder-browse'));

    const dialog = await screen.findByRole('dialog');
    await user.click(await within(dialog).findByRole('button', { name: /handover/ }));

    const select = await screen.findByTestId('path-picker-select-folder');
    await waitFor(() => expect(select).toBeEnabled());
    await user.click(select);

    // 누른 표기(`/nas-storage/handover`)가 아니라 서버가 판정한 실제 위치가 들어간다.
    await waitFor(() =>
      expect(screen.getByLabelText(/산출물 폴더 경로/)).toHaveValue('/nas-storage/handover/real'),
    );
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  /**
   * 허용 저장소 루트가 `/nas-storage` 하나인 배치.
   *
   *   루트 목록            parent 없음(기준 위치 자체가 없다)
   *   /nas-storage         parent=null  ← 자기가 허용 루트라 위가 범위 밖
   *   /nas-storage/handover parent=/nas-storage  ← 부모가 허용 루트지만 범위 안이라 값을 싣는다
   */
  function mockAllowedRootTree() {
    mock
      .onGet('/imports/folders', { params: {} })
      .reply(200, browseBody({ entries: [{ name: 'nas-storage', path: '/nas-storage' }] }));
    mock.onGet('/imports/folders', { params: { path: '/nas-storage' } }).reply(
      200,
      browseBody({
        path: '/nas-storage',
        parent: null,
        entries: [{ name: 'handover', path: '/nas-storage/handover' }],
      }),
    );
    mock.onGet('/imports/folders', { params: { path: '/nas-storage/handover' } }).reply(
      200,
      browseBody({
        path: '/nas-storage/handover',
        parent: '/nas-storage',
        entries: [{ name: '00000073', path: '/nas-storage/handover/00000073' }],
      }),
    );
  }

  it('상위로는_루트_목록에서만_비활성이고_parent가_있으면_그_값으로_없으면_루트_목록으로_간다', async () => {
    // ★ parent 가 비어 있는 것은 「올라갈 곳이 없다」가 아니라 「지금 자리가 허용 저장소 루트」다.
    //   그 값으로 「상위로」를 잠그면 루트 바로 아래에서 갇힌다(구 사양의 결함).
    const user = userEvent.setup();
    mockAllowedRootTree();

    renderWithProviders(<ImportPage />);
    await user.click(screen.getByTestId('import-folder-browse'));

    // ① 루트 목록 — 실제로 더 올라갈 곳이 없으므로 비활성.
    await screen.findByRole('button', { name: /nas-storage/ });
    expect(screen.getByTestId('path-picker-up')).toBeDisabled();

    // ② 허용 루트 안(`/nas-storage`) — parent 는 비어 있지만 「상위로」는 살아 있어야 한다.
    await user.click(screen.getByRole('button', { name: /nas-storage/ }));
    await screen.findByRole('button', { name: /handover/ });
    expect(screen.getByTestId('path-picker-up')).toBeEnabled();

    // ③ 한 단계 더(`/nas-storage/handover`) — parent 가 있으면 그 값으로 이동한다.
    await user.click(screen.getByRole('button', { name: /handover/ }));
    await screen.findByRole('button', { name: /00000073/ });
    await user.click(screen.getByTestId('path-picker-up'));
    await screen.findByRole('button', { name: /handover/ });

    // ④ parent 가 비어 있는 자리에서 누르면 허용 저장소 루트 목록으로 돌아간다.
    await user.click(screen.getByTestId('path-picker-up'));
    await screen.findByRole('button', { name: /nas-storage/ });
    await waitFor(() => expect(screen.getByTestId('path-picker-up')).toBeDisabled());
  });

  it('내려간_만큼_상위로로_되짚어_루트_목록까지_돌아온다', async () => {
    // ★ 이번 결함을 잡는 왕복 가드. 구 판정(disabled={parent === null})에서는 `/nas-storage`
    //   에서 「상위로」가 잠겨 루트 목록으로 되돌아오지 못하고 그 자리에 갇힌다.
    const user = userEvent.setup();
    mockAllowedRootTree();

    renderWithProviders(<ImportPage />);
    await user.click(screen.getByTestId('import-folder-browse'));

    // 루트 목록 → /nas-storage → /nas-storage/handover
    await user.click(await screen.findByRole('button', { name: /nas-storage/ }));
    await user.click(await screen.findByRole('button', { name: /handover/ }));
    await screen.findByRole('button', { name: /00000073/ });

    // 되짚어 올라간다 — 한 번에 뛰지 않고 한 단계씩.
    await user.click(screen.getByTestId('path-picker-up'));
    await screen.findByRole('button', { name: /handover/ });

    await user.click(screen.getByTestId('path-picker-up'));

    // 루트 목록으로 완전히 돌아왔다 — 창을 닫았다 다시 열 필요가 없다.
    await screen.findByRole('button', { name: /nas-storage/ });
    await waitFor(() => expect(screen.getByTestId('path-picker-up')).toBeDisabled());
    // 기준 위치가 없는 목록이므로 「이 폴더 선택」도 다시 잠긴다.
    expect(screen.getByTestId('path-picker-select-folder')).toBeDisabled();
  });

  it('조회_중_상위로를_연타해도_한_단계씩만_올라간다', async () => {
    // ★ 조회가 끝나기 전에는 그 자리의 parent 를 모른다. 모르는 값을 null 로 읽어 그대로 쓰면
    //   「한 단계 위」가 아니라 곧바로 루트 목록으로 튀어 단계를 건너뛴다(AC-120 「한 단계씩」 위반).
    //   그래서 값이 도착할 때까지 「상위로」를 함께 잠근다.
    const user = userEvent.setup();
    // 홀더에 담는다 — 지역 변수로 두면 TS 가 콜백 안의 대입을 못 보고 `never` 로 좁힌다.
    const handover = { release: () => {} };

    mock
      .onGet('/imports/folders', { params: {} })
      .reply(200, browseBody({ entries: [{ name: 'nas-storage', path: '/nas-storage' }] }));
    mock.onGet('/imports/folders', { params: { path: '/nas-storage' } }).reply(
      200,
      browseBody({
        path: '/nas-storage',
        parent: null,
        entries: [{ name: 'handover', path: '/nas-storage/handover' }],
      }),
    );
    // handover 조회만 붙잡아 둔다 — 그 사이가 「parent 를 모르는 구간」이다.
    mock.onGet('/imports/folders', { params: { path: '/nas-storage/handover' } }).reply(
      () =>
        new Promise((resolve) => {
          handover.release = () =>
            resolve([
              200,
              browseBody({
                path: '/nas-storage/handover',
                parent: '/nas-storage',
                entries: [{ name: '00000073', path: '/nas-storage/handover/00000073' }],
              }),
            ]);
        }),
    );

    renderWithProviders(<ImportPage />);
    await user.click(screen.getByTestId('import-folder-browse'));

    await user.click(await screen.findByRole('button', { name: /nas-storage/ }));
    await user.click(await screen.findByRole('button', { name: /handover/ }));

    // 응답이 아직 오지 않은 구간 — 「상위로」는 잠겨 있어야 한다.
    await waitFor(() => expect(screen.getByTestId('path-picker-up')).toBeDisabled());
    // 연타를 모사한다. 잠겨 있으면 아무 일도 일어나지 않는다.
    fireEvent.click(screen.getByTestId('path-picker-up'));
    fireEvent.click(screen.getByTestId('path-picker-up'));

    handover.release();
    await screen.findByRole('button', { name: /00000073/ });

    // 한 번 눌러 한 단계만 올라간다 — 루트 목록이 아니라 /nas-storage 여야 한다.
    await user.click(screen.getByTestId('path-picker-up'));
    expect(await screen.findByRole('button', { name: /handover/ })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /nas-storage$/ })).toBeNull();
  });

  it('탐색이_거부돼도_창을_닫지_않고_서버_메시지를_그_자리에_보여준다', async () => {
    const user = userEvent.setup();
    mock.onGet('/imports/folders', { params: {} }).reply(400, {
      success: false,
      data: null,
      message: '허용된 저장소 범위 밖의 경로입니다.',
      errorCode: 'INVALID_INPUT',
    });

    renderWithProviders(<ImportPage />);
    await user.click(screen.getByTestId('import-folder-browse'));

    expect(await screen.findByTestId('path-picker-error')).toHaveTextContent(
      '허용된 저장소 범위 밖의 경로입니다.',
    );
    // 창이 살아 있어야 사용자가 다른 자리를 다시 열어볼 수 있다.
    expect(screen.getByRole('dialog')).toBeInTheDocument();
  });

  it('탐색이_죽어도_경로를_직접_적어_검사할_수_있다', async () => {
    const user = userEvent.setup();
    mock.onGet('/imports/folders').networkError();
    mock.onPost('/imports/scan').reply(200, {
      success: true,
      data: {
        frameCount: 3,
        declaredFrameCount: 3,
        labelCount: 9,
        videoFileName: '00000073.mp4',
        duplicate: null,
        unmappedCategories: [],
        warnings: [],
        importable: true,
      },
      message: null,
      errorCode: null,
    });

    renderWithProviders(<ImportPage />);
    // 찾아보기를 한 번 열어 실패시킨 뒤 닫아도 폼은 오류 상태가 되지 않는다.
    await user.click(screen.getByTestId('import-folder-browse'));
    await screen.findByTestId('path-picker-error');
    await user.click(screen.getByRole('button', { name: '취소' }));

    await user.type(
      screen.getByLabelText(/산출물 폴더 경로/),
      '/nas-storage/handover/00000073',
    );
    await user.click(screen.getByTestId('import-scan-button'));

    await waitFor(() =>
      expect(mock.history.post.filter((r) => r.url === '/imports/scan')).toHaveLength(1),
    );
  });

  it('영상_파일_찾아보기는_폴더와_영상을_함께_보여주고_파일을_누르면_바로_고른다', async () => {
    const user = userEvent.setup();
    mock
      .onGet('/imports/folders', { params: {} })
      .reply(200, browseBody({ entries: [{ name: 'handover', path: '/nas-storage/handover' }] }));
    mock.onGet('/imports/folders', { params: { path: '/nas-storage/handover' } }).reply(
      200,
      browseBody({ path: '/nas-storage/handover', parent: null, entries: [] }),
    );
    mock.onGet('/imports/files', { params: { path: '/nas-storage/handover' } }).reply(
      200,
      browseBody({
        path: '/nas-storage/handover',
        parent: null,
        entries: [{ name: '00000073.mp4', path: '/nas-storage/handover/00000073.mp4' }],
      }),
    );

    renderWithProviders(<ImportPage />);
    await user.click(screen.getByTestId('import-video-browse'));

    // 루트에서는 위치가 없어 파일 창구를 부르지 않는다(빈 값으로 400 을 부르지 않는다).
    await screen.findByRole('button', { name: /handover/ });
    expect(mock.history.get.filter((r) => r.url === '/imports/files')).toHaveLength(0);

    await user.click(screen.getByRole('button', { name: /handover/ }));
    await user.click(await screen.findByRole('button', { name: /00000073\.mp4/ }));

    await waitFor(() =>
      expect(screen.getByLabelText(/원본 영상 경로/)).toHaveValue(
        '/nas-storage/handover/00000073.mp4',
      ),
    );
    // 파일 축에는 「이 폴더 선택」이 없다 — 목록에서 바로 고른다.
    expect(screen.queryByTestId('path-picker-select-folder')).toBeNull();
  });
});
