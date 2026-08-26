// 이관 대상 위치 탐색 훅 — 폴더 축(API-221)과 영상 파일 축(API-222).
//
// @design API-221 API-222

import { useQuery } from '@tanstack/react-query';

import { IMPORT_KEYS } from '@/lib/queryKeys';

import { listImportFolders, listImportVideoFiles } from '../api';

/**
 * 탐색 결과는 캐시를 붙들지 않는다(`staleTime: 0`).
 *
 * 저장소 마운트가 풀리거나 폴더가 그 사이에 지워질 수 있어, 30초짜리 기본값으로 붙들면 이미
 * 사라진 자리를 고를 수 있게 내주게 된다. 창을 다시 열 때마다 서버에 다시 묻는다.
 */
const BROWSE_QUERY_OPTIONS = { staleTime: 0, retry: false } as const;

/**
 * 하위 폴더 목록. `path` 가 null 이면 허용 저장소 루트 목록을 받는다.
 *
 * 실패해도 재시도하지 않는다 — 400(범위 밖)·404(부재)는 다시 물어도 같은 답이고, 사용자에게
 * 사유를 즉시 보여주는 편이 낫다.
 */
export function useImportFolderBrowse(path: string | null, enabled: boolean) {
  return useQuery({
    queryKey: IMPORT_KEYS.browseFolders(path),
    queryFn: () => listImportFolders(path),
    enabled,
    ...BROWSE_QUERY_OPTIONS,
  });
}

/**
 * 그 폴더 안의 영상 파일 목록.
 *
 * 창구가 `path` 를 필수로 받으므로 루트(=null)에서는 아예 부르지 않는다 — 빈 값을 실어 보내면
 * 서버가 400 으로 거부하고 화면에 근거 없는 오류가 뜬다.
 */
export function useImportVideoFileBrowse(path: string | null, enabled: boolean) {
  return useQuery({
    queryKey: IMPORT_KEYS.browseFiles(path),
    queryFn: () => listImportVideoFiles(path as string),
    enabled: enabled && path !== null,
    ...BROWSE_QUERY_OPTIONS,
  });
}
