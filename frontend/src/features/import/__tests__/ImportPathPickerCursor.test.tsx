// SCREEN-039 「경로 선택」 — 나눠 받아 **이어붙이는** 흐름의 회귀 가드.
//
// 못 박는 것 (AC-120):
//   · 이어받을 자리가 있을 때만 「더 보기」가 보이고, 누르면 목록 **아래에 이어붙는다**.
//   · ★담긴 것이 0건이어도 이어받을 자리가 있으면 「더 보기」가 그대로 보인다 — 그리고 그때
//     「폴더가 없습니다」라고 말하지 않는다(폴더가 있는데 없는 것으로 보이는 결함).
//   · 「더 보기」를 눌렀는데 새로 담긴 것이 없으면 목록도 버튼도 그대로 두고 사실만 알린다.
//     **자동으로 다시 이어받지 않는다.**
//   · 자리를 옮기면 이어받기가 처음으로 돌아가 이전 자리의 항목이 남지 않는다.
//   · ★창을 닫았다 **다시 열어도** 이어받기가 처음으로 돌아간다 — 자리가 그대로면 쿼리 키도
//     그대로라 저절로 되지 않는다. 창을 열 때 쌓인 쪽을 버리는 배선이 유일한 수단이다.
//   · 안내 문구는 **고르는 대상에 따라 갈린다**(폴더 축 / 영상 파일 축).
//   · 허용 저장소 루트 목록에는 「더 보기」가 뜨지 않는다 — 그 목록은 나눠 주지 않는다.
//
// ⚠ 커버리지 경계 — 덮은 척하지 않는다.
//   axios 를 모의하므로 서버가 커서를 실제로 **배타**로 다루는지, 정렬이 정말 고정인지는 볼 수
//   없다. 그 축은 BE 시험이 맡는다. 여기 픽스처는 **서버가 실제로 만들 수 있는 응답**만 쓴다.
//
// @design SCREEN-039 API-221 API-222 AC-120

import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { QueryClient } from '@tanstack/react-query';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor, within } from '@testing-library/react';
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
    data: { path: null, parent: null, entries: [], nextCursor: null, ...data },
    message: null,
    errorCode: null,
  };
}

/**
 * 이 파일의 **재개 가드 전용** 클라이언트 — `gcTime` 을 프로덕션 기본값으로 둔다.
 *
 * 공용 `createTestQueryClient` 는 `gcTime: 0` 이라, 관찰자가 떨어지는 순간 캐시가 함께 사라진다.
 * 그러면 「창을 다시 열 때 쌓인 쪽을 버린다」는 배선을 통째로 지워도 **시험이 그대로 통과**한다 —
 * 버릴 것이 이미 없기 때문이다. 가짜 초록불을 만들지 않으려면 이 축만은 캐시를 붙들어 둔 채로
 * 확인해야 한다.
 *
 * ⚠ 지금 형상에서는 이 창이 닫혀도 컴포넌트 자체는 붙어 있어(모달 본문만 사라진다) 관찰자가
 *   떨어지지 않는다 — 그래서 `gcTime` 이 실제로 판정을 가르지는 않는다. 그럼에도 이 클라이언트를
 *   쓰는 이유는, 닫힐 때 본문을 아예 떼어내는 형태로 바뀌는 순간 공용 클라이언트가 이 가드를
 *   **조용히 무력화**하기 때문이다. 잃는 것이 없고 막는 것이 크다.
 */
function createRetainingQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false, staleTime: 0, refetchOnWindowFocus: false },
      mutations: { retry: false },
    },
  });
}

describe('SCREEN-039 경로 선택 — 이어받기', () => {
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

  it('더_보기를_누르면_그_자리_뒤부터_받아_목록_아래에_이어붙인다', async () => {
    const user = userEvent.setup();
    // 루트 목록 — 첫 쪽에 sub-1 하나, 이어받을 자리 있음.
    mock
      .onGet('/imports/folders', { params: {} })
      .reply(200, browseBody({ entries: [{ name: 'sub-1', path: '/nas/sub-1' }], nextCursor: 'sub-1' }));
    mock
      .onGet('/imports/folders', { params: { cursor: 'sub-1' } })
      .reply(200, browseBody({ entries: [{ name: 'sub-2', path: '/nas/sub-2' }], nextCursor: null }));

    renderWithProviders(<ImportPage />);
    await user.click(screen.getByTestId('import-folder-browse'));

    await screen.findByRole('button', { name: /sub-1/ });
    // 첫 쪽에서는 sub-2 가 아직 없다.
    expect(screen.queryByRole('button', { name: /sub-2/ })).toBeNull();

    await user.click(screen.getByTestId('path-picker-load-more'));

    // 앞서 받은 것이 사라지지 않고 **아래에 이어붙는다**.
    await screen.findByRole('button', { name: /sub-2/ });
    expect(screen.getByRole('button', { name: /sub-1/ })).toBeInTheDocument();

    const list = screen.getByTestId('path-picker-list');
    const names = within(list)
      .getAllByRole('button')
      .map((b) => b.textContent?.trim());
    expect(names).toEqual(['sub-1', 'sub-2']);

    // 끝까지 봤으므로 「더 보기」가 사라진다.
    await waitFor(() => expect(screen.queryByTestId('path-picker-load-more')).toBeNull());
  });

  it('★담긴_것이_0건이어도_이어받을_자리가_있으면_더_보기가_보이고_없음이라_말하지_않는다', async () => {
    // ★ 이 화면에서 가장 틀리기 쉬운 지점. 서버가 **살펴보기 상한**에 먼저 걸리면 담긴 것이
    //   하나도 없이 이어받을 자리만 돌아온다(API-221) — 정상 응답이다.
    //   개수로 끝을 판정하면 「이 자리에 하위 폴더가 없습니다」가 떠 **폴더가 있는데 없는 것으로
    //   보인다**.
    const user = userEvent.setup();
    mock
      .onGet('/imports/folders', { params: {} })
      .reply(200, browseBody({ entries: [], nextCursor: 'sub-00200' }));

    renderWithProviders(<ImportPage />);
    await user.click(screen.getByTestId('import-folder-browse'));

    // 담긴 것이 0건이어도 아직 끝이 아니다.
    expect(await screen.findByTestId('path-picker-load-more')).toBeInTheDocument();
    // 끝까지 다 본 것이 아니므로 「없다」고 말하지 않는다.
    expect(screen.queryByText(/하위 폴더가 없습니다/)).toBeNull();
  });

  it('끝까지_본_뒤에만_이_자리에_없다고_말한다', async () => {
    const user = userEvent.setup();
    mock
      .onGet('/imports/folders', { params: {} })
      .reply(200, browseBody({ entries: [], nextCursor: null }));

    renderWithProviders(<ImportPage />);
    await user.click(screen.getByTestId('import-folder-browse'));

    expect(await screen.findByText(/하위 폴더가 없습니다/)).toBeInTheDocument();
    expect(screen.queryByTestId('path-picker-load-more')).toBeNull();
  });

  it('더_보기를_눌렀는데_새로_담긴_것이_없으면_목록과_버튼을_그대로_두고_알린다', async () => {
    // 살펴보기 상한에 걸린 경우다. ★자동으로 다시 이어받지 않는다 — 몇 번을 도는지 화면이
    // 통제하지 못하기 때문이다.
    const user = userEvent.setup();
    mock
      .onGet('/imports/folders', { params: {} })
      .reply(200, browseBody({ entries: [{ name: 'sub-1', path: '/nas/sub-1' }], nextCursor: 'sub-1' }));
    mock
      .onGet('/imports/folders', { params: { cursor: 'sub-1' } })
      .reply(200, browseBody({ entries: [], nextCursor: 'sub-9' }));

    renderWithProviders(<ImportPage />);
    await user.click(screen.getByTestId('import-folder-browse'));
    await screen.findByRole('button', { name: /sub-1/ });

    await user.click(screen.getByTestId('path-picker-load-more'));

    expect(await screen.findByTestId('path-picker-no-new')).toHaveTextContent(
      '살펴본 자리에는 폴더가 없었습니다. 계속 볼 수 있습니다.',
    );
    // 목록도 버튼도 그대로다.
    expect(screen.getByRole('button', { name: /sub-1/ })).toBeInTheDocument();
    expect(screen.getByTestId('path-picker-load-more')).toBeInTheDocument();

    // 자동으로 다시 이어받지 않는다 — 요청은 사람이 누른 두 번뿐이다.
    await waitFor(() =>
      expect(mock.history.get.filter((r) => r.url === '/imports/folders')).toHaveLength(2),
    );
  });

  it('자리를_옮기면_이어받기가_처음으로_돌아가_이전_자리의_항목이_남지_않는다', async () => {
    const user = userEvent.setup();
    mock
      .onGet('/imports/folders', { params: {} })
      .reply(200, browseBody({ entries: [{ name: 'sub-1', path: '/nas/sub-1' }], nextCursor: 'sub-1' }));
    mock
      .onGet('/imports/folders', { params: { cursor: 'sub-1' } })
      .reply(200, browseBody({ entries: [{ name: 'sub-2', path: '/nas/sub-2' }], nextCursor: null }));
    // 한 단계 내려간 자리 — 완전히 다른 목록이다.
    mock.onGet('/imports/folders', { params: { path: '/nas/sub-1' } }).reply(
      200,
      browseBody({
        path: '/nas/sub-1',
        parent: null,
        entries: [{ name: 'deep', path: '/nas/sub-1/deep' }],
        nextCursor: null,
      }),
    );

    renderWithProviders(<ImportPage />);
    await user.click(screen.getByTestId('import-folder-browse'));

    // 루트에서 두 쪽을 쌓는다.
    await screen.findByRole('button', { name: /sub-1/ });
    await user.click(screen.getByTestId('path-picker-load-more'));
    await screen.findByRole('button', { name: /sub-2/ });

    // 한 단계 내려간다.
    await user.click(screen.getByRole('button', { name: /sub-1/ }));

    await screen.findByRole('button', { name: /deep/ });
    // ★ 앞 자리에 쌓아 둔 항목이 따라오면 **없는 폴더를 고를 수 있게** 된다.
    expect(screen.queryByRole('button', { name: /sub-2/ })).toBeNull();
    // 새 자리는 끝까지 본 상태이므로 「더 보기」도 없다.
    expect(screen.queryByTestId('path-picker-load-more')).toBeNull();
  });

  it('★창을_다시_열면_쌓아_둔_쪽을_버리고_처음부터_다시_받는다', async () => {
    // ★ 자리를 옮기는 경우(바로 위 케이스)와 **다른 축**이다. 자리가 바뀌면 쿼리 키가 갈려
    //   이어받기가 저절로 처음으로 돌아가지만, **같은 자리에서 창만 다시 여는 경우는 키가
    //   그대로**라 앞 회차에 쌓아 둔 쪽들이 남는다. 창을 열 때 그것을 버리는 배선이 유일한
    //   수단이며, 그 배선을 지워도 위 케이스는 그대로 통과한다 — 그래서 따로 못 박는다.
    const user = userEvent.setup();
    mock
      .onGet('/imports/folders', { params: {} })
      .reply(200, browseBody({ entries: [{ name: 'sub-1', path: '/nas/sub-1' }], nextCursor: 'sub-1' }));
    mock
      .onGet('/imports/folders', { params: { cursor: 'sub-1' } })
      .reply(200, browseBody({ entries: [{ name: 'sub-2', path: '/nas/sub-2' }], nextCursor: null }));

    // ⚠ 공용 클라이언트가 아니라 캐시를 붙들어 두는 쪽을 쓴다 — 사유는 위 주석 참조.
    renderWithProviders(<ImportPage />, { queryClient: createRetainingQueryClient() });
    await user.click(screen.getByTestId('import-folder-browse'));

    // 루트에서 두 쪽을 쌓는다.
    await screen.findByRole('button', { name: /sub-1/ });
    await user.click(screen.getByTestId('path-picker-load-more'));
    await screen.findByRole('button', { name: /sub-2/ });

    // 창을 닫았다가 **같은 자리에서** 다시 연다.
    await user.click(screen.getByRole('button', { name: '취소' }));
    expect(screen.queryByTestId('import-path-picker')).toBeNull();
    await user.click(screen.getByTestId('import-folder-browse'));

    // ★ 첫 쪽만 있는 상태로 돌아온다 — 이미 여러 번 이어받은 상태로 열리지 않는다.
    await waitFor(() => {
      const list = screen.getByTestId('path-picker-list');
      const names = within(list)
        .getAllByRole('button')
        .map((b) => b.textContent?.trim());
      expect(names).toEqual(['sub-1']);
    });
    // 다시 처음부터이므로 이어받을 자리도 되살아나 있다.
    expect(screen.getByTestId('path-picker-load-more')).toBeInTheDocument();
  });

  it('★영상_파일_축의_안내는_폴더도_영상_파일도_없었다고_말한다', async () => {
    // ★ 안내 문구는 **고르는 대상에 따라 갈린다**(SCREEN-039). 이 자리에서는 하위 폴더로 더
    //   내려갈 수도 있고 그 안의 영상 파일을 고를 수도 있으므로, 영상 파일만 두고 말하면
    //   **길이 있는데 없다고 말하는** 셈이 된다. 그래서 두 종류를 함께 말한다.
    //   폴더 축 문구(바로 위 케이스)로 되돌려도 그 케이스는 통과하므로 이 자리를 따로 덮는다.
    const user = userEvent.setup();
    mock
      .onGet('/imports/folders', { params: {} })
      .reply(200, browseBody({ entries: [{ name: 'handover', path: '/nas/handover' }] }));
    // 그 자리의 하위 폴더는 끝까지 봤고,
    mock
      .onGet('/imports/folders', { params: { path: '/nas/handover' } })
      .reply(200, browseBody({ path: '/nas/handover', parent: null, entries: [] }));
    // 영상 파일 쪽에만 이어받을 자리가 남아 있다.
    mock.onGet('/imports/files', { params: { path: '/nas/handover' } }).reply(
      200,
      browseBody({
        path: '/nas/handover',
        entries: [{ name: 'a.mp4', path: '/nas/handover/a.mp4' }],
        nextCursor: 'a.mp4',
      }),
    );
    // 살펴보기 상한에 먼저 걸려 담긴 것 없이 이어받을 자리만 돌아온다.
    mock
      .onGet('/imports/files', { params: { path: '/nas/handover', cursor: 'a.mp4' } })
      .reply(200, browseBody({ path: '/nas/handover', entries: [], nextCursor: 'z.mp4' }));

    renderWithProviders(<ImportPage />);
    await user.click(screen.getByTestId('import-video-browse'));

    await user.click(await screen.findByRole('button', { name: /handover/ }));
    await screen.findByRole('button', { name: /a\.mp4/ });

    await user.click(screen.getByTestId('path-picker-load-more'));

    expect(await screen.findByTestId('path-picker-no-new')).toHaveTextContent(
      '살펴본 자리에는 폴더도 영상 파일도 없었습니다. 계속 볼 수 있습니다.',
    );
  });

  it('허용_저장소_루트_목록에는_더_보기가_뜨지_않는다', async () => {
    // 루트 목록은 설정에서 나와 크기가 고정돼 있고 저장 장치를 훑지도 않으므로, 서버가
    // **나눠 주지 않고 전부 싣고 이어받을 자리를 비워** 돌려준다(API-221·AC-120).
    // 화면은 이어받을 자리 유무로만 판정하므로 이 상태에서 「더 보기」가 뜨면 안 된다.
    // ⚠ 이 케이스가 덮는 것은 **그 계약을 화면이 그대로 따른다**는 것까지다. 서버가 루트도
    //   나눠 주기로 바뀌면 화면은 순순히 「더 보기」를 띄우므로, 이 시험이 그 변화를 막지는
    //   않는다 — 계약 축은 BE 시험이 담당한다.
    const user = userEvent.setup();
    mock.onGet('/imports/folders', { params: {} }).reply(
      200,
      browseBody({
        entries: [
          { name: 'raw', path: '/nas/raw' },
          { name: 'deidentified', path: '/nas/deidentified' },
          { name: 'genai-out', path: '/nas/genai-out' },
        ],
        nextCursor: null,
      }),
    );

    renderWithProviders(<ImportPage />);
    await user.click(screen.getByTestId('import-folder-browse'));

    await screen.findByRole('button', { name: /raw/ });
    // 담긴 것이 여럿이어도 이어받을 자리가 비었으면 끝이다.
    expect(screen.queryByTestId('path-picker-load-more')).toBeNull();
    // 끝까지 본 것이되 항목이 있으므로 「없습니다」도 뜨지 않는다.
    expect(screen.queryByText(/하위 폴더가 없습니다/)).toBeNull();
  });

  it('영상_파일_축도_이어받는다', async () => {
    const user = userEvent.setup();
    mock
      .onGet('/imports/folders', { params: {} })
      .reply(200, browseBody({ entries: [{ name: 'handover', path: '/nas/handover' }] }));
    mock
      .onGet('/imports/folders', { params: { path: '/nas/handover' } })
      .reply(200, browseBody({ path: '/nas/handover', parent: null, entries: [] }));
    mock
      .onGet('/imports/files', { params: { path: '/nas/handover' } })
      .reply(
        200,
        browseBody({
          path: '/nas/handover',
          entries: [{ name: 'a.mp4', path: '/nas/handover/a.mp4' }],
          nextCursor: 'a.mp4',
        }),
      );
    mock
      .onGet('/imports/files', { params: { path: '/nas/handover', cursor: 'a.mp4' } })
      .reply(
        200,
        browseBody({
          path: '/nas/handover',
          entries: [{ name: 'b.mp4', path: '/nas/handover/b.mp4' }],
          nextCursor: null,
        }),
      );

    renderWithProviders(<ImportPage />);
    await user.click(screen.getByTestId('import-video-browse'));

    await user.click(await screen.findByRole('button', { name: /handover/ }));
    await screen.findByRole('button', { name: /a\.mp4/ });

    await user.click(screen.getByTestId('path-picker-load-more'));
    await screen.findByRole('button', { name: /b\.mp4/ });
    expect(screen.getByRole('button', { name: /a\.mp4/ })).toBeInTheDocument();
  });
});
