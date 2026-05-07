import { useQuery } from '@tanstack/react-query';

import { VERSION_KEYS } from '@/lib/queryKeys';

import { getDiff } from '../api';

/**
 * 두 커밋 간 라벨 diff 조회.
 *
 * @param videoId   query key 격리용 (실제 BE는 commit hash만 받음)
 * @param commit    기준 커밋
 * @param compareWith 비교 대상 커밋 (없으면 BE가 부모와 비교)
 */
export function useDiff(
  videoId: number | undefined,
  commit: string | undefined,
  compareWith: string | undefined,
) {
  return useQuery({
    queryKey:
      videoId !== undefined && commit && compareWith
        ? VERSION_KEYS.diff(videoId, commit, compareWith)
        : VERSION_KEYS.all,
    queryFn: () => getDiff(commit as string, compareWith),
    enabled: videoId !== undefined && Boolean(commit) && Boolean(compareWith),
  });
}
