// 이관 대상 탐색 「이어받기」 — 커서가 흘러야 할 **단**의 인접 가드.
//
// 커서는 네 단을 지나야 기능이 성립한다:
//   1단 「더 보기」(모달)  2단 훅의 쪽 인자(`pageParam`)  3단 요청 파라미터  4단 응답의 `nextCursor`
//
// ★ 이 파일은 2~4단을 **인접하게 묶어** 고정한다. 양 끝만 가드하면 가운데가 통째로 빠져도
//   전건 green 이 될 수 있다 — 직전 라운드에 실제로 그런 일이 있었다.
//
// 못 박는 것 (AC-120 · API-221 · API-222):
//   · 이어받을 자리가 **실제 요청 파라미터**에 실린다(응답 픽스처가 아니라 **호출 인자**를 단언).
//   · ★끝 판정은 담긴 개수가 아니라 `nextCursor` 가 비었는지로 한다 — 담긴 것이 0건이어도
//     이어받을 자리가 있으면 끝이 아니다.
//   · 이어붙일 때 위치를 키로 합쳐 같은 항목이 두 번 쌓이지 않는다.
//   · 커서는 **쿼리 키에 들어가지 않는다** — 넣으면 쪽마다 캐시가 갈려 이어붙이기가 깨진다.
//
// ⚠ 커버리지 경계 — 덮은 척하지 않는다.
//   이 파일은 axios 를 모의해 **요청이 무엇을 실어 나르고 응답을 어떻게 읽는지**까지만 고정한다.
//   서버가 커서를 어떻게 해석하는지(배타인지 포함인지, 정렬이 실제로 고정인지)는 볼 수 없다.
//   응답 픽스처를 설계 문면대로 만드는 한 **BE 와 FE 가 같은 방향으로 함께 틀린 결함은 원리적으로
//   잡히지 않는다** — 직전 라운드의 「상위로」 갇힘이 그 종류였고 런타임까지 가서야 드러났다.
//   ★그래서 이 파일의 픽스처는 **서버가 실제로 만들 수 있는 응답**만 쓴다. 「루트 목록이 잘린다」
//   처럼 서버가 만들 수 없는 상황을 전제로 깔면 그 가드는 아무것도 지키지 못한다(직전 라운드 실패).
//
// @design API-221 API-222 AC-120 SCREEN-039

import type { ReactNode } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { renderHook, waitFor } from '@testing-library/react';
import MockAdapter from 'axios-mock-adapter';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { apiClient } from '@/lib/api/client';
import { IMPORT_KEYS } from '@/lib/queryKeys';

import { mergeBrowseEntries } from '../browsePages';
import { useImportFolderBrowse, useImportVideoFileBrowse } from '../hooks/useImportBrowse';
import type { ImportBrowseResult } from '../types';

const HERE = '/nas-storage/handover';

function wrapper(qc: QueryClient) {
  const Wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={qc}>{children}</QueryClientProvider>
  );
  Wrapper.displayName = 'TestQueryClientWrapper';
  return Wrapper;
}

function newClient() {
  return new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0, staleTime: 0 } },
  });
}

function body(data: Partial<ImportBrowseResult>) {
  return {
    success: true,
    data: { path: HERE, parent: null, entries: [], nextCursor: null, ...data },
    message: null,
    errorCode: null,
  };
}

/** 이번 실행에서 그 창구로 나간 요청들의 질의 파라미터. */
function paramsOf(mock: MockAdapter, url: string): Record<string, string>[] {
  return mock.history.get
    .filter((r) => r.url === url)
    .map((r) => (r.params ?? {}) as Record<string, string>);
}

/**
 * 이어받을 자리에 따라 답을 고르는 목 — **실제 창구처럼** 커서를 읽어 분기한다.
 *
 * ⚠ 응답을 호출 **순서**로 늘어놓지 않는다. 이 탐색은 캐시를 붙들지 않아 재조회가 끼어들 수
 *   있고, 그러면 순서 기반 목은 엉뚱한 답을 돌려줘 **가드가 배선이 아니라 호출 순서를**
 *   고정하게 된다. 커서로 분기하면 몇 번을 다시 물어도 같은 답이 온다.
 */
function replyByCursor(
  mock: MockAdapter,
  url: string,
  pages: Record<string, Partial<ImportBrowseResult>>,
) {
  mock.onGet(url).reply((config) => {
    const cursor = (config.params as Record<string, string> | undefined)?.cursor ?? '';
    return [200, body(pages[cursor] ?? { entries: [], nextCursor: null })];
  });
}

/**
 * ★<b>렌더 중에 읽은 속성만 다시 그리기를 부른다</b>(React Query v5 의 tracked properties).
 *
 * {@code renderHook(() => useImportFolderBrowse(...))} 처럼 결과를 <b>그대로 돌려주기만</b> 하면
 * 렌더 중에 읽은 속성이 하나도 없어, 나중에 쪽이 늘어도 관찰자가 다시 그리지 않는다 —
 * {@code result.current} 가 <b>첫 쪽에 멈춘 채로 남는다</b>. 캐시에는 두 쪽이 멀쩡히 들어 있는데
 * 시험만 실패해, 원인을 목이나 배선에서 찾다 헤매게 된다(실제로 그렇게 한 번 헛짚었다).
 *
 * 그래서 이 감싸개가 <b>렌더 중에</b> 필요한 속성을 읽어 둔다.
 *
 * ⚠ 이것은 <b>시험 쪽 사정</b>이지 제품 결함이 아니다 — 모달은 렌더 중에 {@code data}·
 *   {@code hasNextPage}·{@code isFetching} 을 읽으므로 그 속성들이 이미 추적된다.
 */
function useBrowseProbe(query: {
  data?: { pages: ImportBrowseResult[] };
  hasNextPage: boolean;
  isSuccess: boolean;
  fetchNextPage: () => Promise<unknown>;
}) {
  return {
    pages: query.data?.pages,
    hasNextPage: query.hasNextPage,
    isSuccess: query.isSuccess,
    fetchNextPage: query.fetchNextPage,
  };
}

describe('이관 대상 탐색 — 이어받기의 단 배선', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
  });

  afterEach(() => {
    mock.restore();
  });

  it('이어받을_자리가_실제_요청_파라미터에_실린다_폴더축', async () => {
    // ★ 응답 픽스처가 아니라 **호출 인자**를 단언한다 — 커서를 요청에 싣지 않고도 응답만
    //   갈아끼우면 통과하는 가드가 되지 않게.
    replyByCursor(mock, '/imports/folders', {
      '': { entries: [{ name: 'a', path: `${HERE}/a` }], nextCursor: 'a' },
      a: { entries: [{ name: 'b', path: `${HERE}/b` }], nextCursor: null },
    });

    const qc = newClient();
    const { result } = renderHook(() => useImportFolderBrowse(HERE, true), {
      wrapper: wrapper(qc),
    });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    // 첫 요청은 자리만 싣고 커서는 **키 자체를 싣지 않는다**.
    expect(paramsOf(mock, '/imports/folders')[0]).toEqual({ path: HERE });

    await result.current.fetchNextPage();
    await waitFor(() => expect(paramsOf(mock, '/imports/folders')).toHaveLength(2));

    // 두 번째 요청에 앞선 응답의 `nextCursor` 가 그대로 실려 나간다.
    expect(paramsOf(mock, '/imports/folders')[1]).toEqual({ path: HERE, cursor: 'a' });
  });

  it('이어받을_자리가_실제_요청_파라미터에_실린다_영상파일축', async () => {
    replyByCursor(mock, '/imports/files', {
      '': { entries: [{ name: 'a.mp4', path: `${HERE}/a.mp4` }], nextCursor: 'a.mp4' },
      'a.mp4': { entries: [], nextCursor: null },
    });

    const qc = newClient();
    const { result } = renderHook(() => useImportVideoFileBrowse(HERE, true), {
      wrapper: wrapper(qc),
    });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    await result.current.fetchNextPage();
    await waitFor(() => expect(paramsOf(mock, '/imports/files')).toHaveLength(2));

    expect(paramsOf(mock, '/imports/files')[1]).toEqual({ path: HERE, cursor: 'a.mp4' });
  });

  it('★담긴_것이_0건이어도_이어받을_자리가_있으면_끝이_아니다', async () => {
    // ★ 이 화면에서 가장 틀리기 쉬운 지점. 서버는 **살펴보는 항목 수**에도 따로 상한을 두므로
    //   종류가 맞지 않는 항목이 이어지면 **하나도 못 담은 채** 상한에 먼저 걸린다(API-221).
    //   그때도 이어받을 자리는 함께 돌아온다 — 서버가 실제로 만들 수 있는 응답이다.
    //
    //   개수로 끝을 판정하면 그 폴더의 나머지가 통째로 사라지고, 화면에는 「폴더가 없습니다」로
    //   보인다. 폴더가 있는데 없는 것으로 보이는 것이다.
    mock.onGet('/imports/folders').reply(200, body({ entries: [], nextCursor: 'sub-00200' }));

    const qc = newClient();
    const { result } = renderHook(() => useBrowseProbe(useImportFolderBrowse(HERE, true)), {
      wrapper: wrapper(qc),
    });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(mergeBrowseEntries(result.current.pages)).toHaveLength(0);
    // 담긴 것은 0건인데 아직 끝이 아니다.
    expect(result.current.hasNextPage).toBe(true);
  });

  it('이어받을_자리가_비면_끝이다', async () => {
    mock
      .onGet('/imports/folders')
      .reply(200, body({ entries: [{ name: 'a', path: `${HERE}/a` }], nextCursor: null }));

    const qc = newClient();
    const { result } = renderHook(() => useBrowseProbe(useImportFolderBrowse(HERE, true)), {
      wrapper: wrapper(qc),
    });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    // 담긴 것이 있어도 이어받을 자리가 비었으면 끝이다.
    expect(result.current.hasNextPage).toBe(false);
  });

  it('★같은_항목이_두_번_와도_한_번만_쌓인다', async () => {
    // ⚠ 계약상 이어받는 자리는 **배타**라 중복이 오지 않는다(API-221). 그런데도 이 가드를 두는
    //   이유는 **중복이 오는 길이 계약 밖에 따로 있기** 때문이다 — 이 탐색은 캐시를 붙들지 않아
    //   재조회가 걸리면 쌓인 쪽을 처음부터 다시 받아오고, 그 사이 저장소 내용이 바뀌면 같은
    //   항목이 두 쪽에 함께 담긴다. 그 상황을 픽스처로 만들어 확인한다.
    const dup = { name: 'sub-1', path: `${HERE}/sub-1` };
    const pages: ImportBrowseResult[] = [
      { path: HERE, parent: null, entries: [dup, { name: 'sub-2', path: `${HERE}/sub-2` }], nextCursor: 'sub-2' },
      { path: HERE, parent: null, entries: [dup, { name: 'sub-3', path: `${HERE}/sub-3` }], nextCursor: null },
    ];

    const merged = mergeBrowseEntries(pages);

    expect(merged.map((e) => e.path)).toEqual([
      `${HERE}/sub-1`,
      `${HERE}/sub-2`,
      `${HERE}/sub-3`,
    ]);
    // 먼저 온 것을 남긴다 — 정렬이 이름 오름차순으로 고정돼 있어 앞쪽 쪽이 목록에서도 앞이다.
    expect(merged.filter((e) => e.path === `${HERE}/sub-1`)).toHaveLength(1);
  });

  it('이름이_같아도_위치가_다르면_따로_쌓인다', async () => {
    // 합치는 키는 이름이 아니라 **위치**다 — 이름은 서로 다른 자리에서 같을 수 있다.
    const merged = mergeBrowseEntries([
      { path: HERE, parent: null, entries: [{ name: 'sub', path: `${HERE}/a/sub` }], nextCursor: 'x' },
      { path: HERE, parent: null, entries: [{ name: 'sub', path: `${HERE}/b/sub` }], nextCursor: null },
    ]);
    expect(merged).toHaveLength(2);
  });

  it('★이어받을_자리는_쿼리_키에_들어가지_않는다', async () => {
    // ★ 커서를 키에 넣으면 쪽마다 별개의 캐시 자리가 생겨 **이어붙이기가 성립하지 않는다** —
    //   「더 보기」를 누를 때마다 앞서 받은 목록이 사라지고 그 쪽 하나만 남는다.
    //   키를 가르는 축은 **자리(path)와 창구(폴더/파일)** 둘뿐이다.
    expect(IMPORT_KEYS.browseFolders(HERE)).toEqual(IMPORT_KEYS.browseFolders(HERE));
    expect(IMPORT_KEYS.browseFolders(HERE)).not.toEqual(IMPORT_KEYS.browseFolders(null));
    // 같은 자리여도 담는 것이 달라 두 창구는 키가 갈린다.
    expect(IMPORT_KEYS.browseFolders(HERE)).not.toEqual(IMPORT_KEYS.browseFiles(HERE));
    // 키에 커서가 섞이지 않았음을 직접 확인한다.
    expect(IMPORT_KEYS.browseFolders(HERE)).toEqual([
      'imports',
      'browse',
      'folders',
      HERE,
    ]);
  });

  it('나눠_받은_쪽들이_한_캐시_자리에_쌓인다', async () => {
    // 키가 그대로이므로 두 쪽이 같은 자리에 함께 남는다 — 이어붙이기의 전제다.
    replyByCursor(mock, '/imports/folders', {
      '': { entries: [{ name: 'a', path: `${HERE}/a` }], nextCursor: 'a' },
      a: { entries: [{ name: 'b', path: `${HERE}/b` }], nextCursor: null },
    });

    const qc = newClient();
    const { result } = renderHook(() => useBrowseProbe(useImportFolderBrowse(HERE, true)), {
      wrapper: wrapper(qc),
    });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    await result.current.fetchNextPage();

    // `result.current` 는 마지막 렌더의 스냅샷이라 다시 그려질 때까지 기다렸다 읽는다.
    await waitFor(() => expect(result.current.pages).toHaveLength(2));
    expect(mergeBrowseEntries(result.current.pages).map((e) => e.name)).toEqual(['a', 'b']);
    // 두 쪽을 다 받았으므로 더 받을 것이 없다.
    expect(result.current.hasNextPage).toBe(false);
  });
});
