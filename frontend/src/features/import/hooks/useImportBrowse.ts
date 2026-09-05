// 이관 대상 위치 탐색 훅 — 폴더 축(API-221)과 영상 파일 축(API-222).
//
// @design API-221 API-222 AC-120

import { useInfiniteQuery } from '@tanstack/react-query';

import { IMPORT_KEYS } from '@/lib/queryKeys';

import { listImportFolders, listImportVideoFiles } from '../api';
import type { ImportBrowseResult } from '../types';

/**
 * 탐색 결과는 캐시를 붙들지 않는다(`staleTime: 0`).
 *
 * 저장소 마운트가 풀리거나 폴더가 그 사이에 지워질 수 있어, 30초짜리 기본값으로 붙들면 이미
 * 사라진 자리를 고를 수 있게 내주게 된다. 창을 다시 열 때마다 서버에 다시 묻는다.
 *
 * ⚠ 무한 조회에서 재조회는 **쌓인 쪽을 처음부터 다시** 받아온다. 그 사이 저장소 내용이 바뀌면
 *   같은 항목이 두 쪽에 함께 담길 수 있어, 합치는 자리에서 위치를 키로 걷어낸다
 *   (`browsePages.mergeBrowseEntries`).
 */
const BROWSE_QUERY_OPTIONS = { staleTime: 0, retry: false } as const;

/**
 * 이어받을 자리를 다음 쪽 인자로 넘긴다. **판정은 이 함수 한 곳에만 둔다.**
 *
 * ★<b>끝났는지는 담긴 개수가 아니라 `nextCursor` 가 비었는지로 판정한다</b>(API-221·AC-120).
 * 서버는 살펴보는 항목 수에도 따로 상한을 두므로, 담을 것이 종류에 맞지 않아 <b>하나도 못
 * 담은 채</b> 상한에 먼저 걸릴 수 있다. 그때도 이어받을 자리는 함께 돌아온다.
 *
 * ⚠ {@code entries.length} 로 끝을 판정하면 <b>그 폴더의 나머지가 통째로 사라진다</b> —
 *   화면에는 「이 자리에 폴더가 없습니다」로 보이는데 실제로는 아직 다 보지도 않은 것이다.
 *   이 프로젝트에서 가장 틀리기 쉬운 지점이라 시험으로 따로 못 박는다.
 *
 * `undefined` 를 돌려주면 React Query 가 「더 받을 것이 없다」로 읽는다(`hasNextPage=false`).
 * 계약이 비어 있음을 `null` 로 표현하므로 여기서 한 번만 옮겨 준다.
 */
function nextCursorOf(last: ImportBrowseResult): string | undefined {
  return last.nextCursor ?? undefined;
}

/**
 * 하위 폴더 목록. `path` 가 null 이면 허용 저장소 루트 목록을 받는다.
 *
 * 한 번에 다 받지 않고 나눠서 이어 받는다 — 이어받을 자리는 <b>쪽 인자</b>로 흐르고 받아온
 * 쪽들은 한 캐시 자리에 쌓인다(쿼리 키에 커서를 넣지 않는 이유는 `IMPORT_KEYS` 참조).
 *
 * 실패해도 재시도하지 않는다 — 400(범위 밖)·404(부재)는 다시 물어도 같은 답이고, 사용자에게
 * 사유를 즉시 보여주는 편이 낫다.
 */
export function useImportFolderBrowse(path: string | null, enabled: boolean) {
  return useInfiniteQuery({
    queryKey: IMPORT_KEYS.browseFolders(path),
    queryFn: ({ pageParam }) => listImportFolders(path, pageParam),
    initialPageParam: null as string | null,
    getNextPageParam: nextCursorOf,
    enabled,
    ...BROWSE_QUERY_OPTIONS,
  });
}

/**
 * 그 폴더 안의 영상 파일 목록.
 *
 * 창구가 `path` 를 필수로 받으므로 루트(=null)에서는 아예 부르지 않는다 — 빈 값을 실어 보내면
 * 서버가 400 으로 거부하고 화면에 근거 없는 오류가 뜬다.
 *
 * 이어받기 규약은 폴더 축과 같다.
 */
export function useImportVideoFileBrowse(path: string | null, enabled: boolean) {
  return useInfiniteQuery({
    queryKey: IMPORT_KEYS.browseFiles(path),
    queryFn: ({ pageParam }) => listImportVideoFiles(path as string, pageParam),
    initialPageParam: null as string | null,
    getNextPageParam: nextCursorOf,
    enabled: enabled && path !== null,
    ...BROWSE_QUERY_OPTIONS,
  });
}
