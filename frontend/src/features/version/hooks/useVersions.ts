import { useQuery } from '@tanstack/react-query';

import { VERSION_KEYS } from '@/lib/queryKeys';

import { listVersions } from '../api';

/**
 * 영상의 버전(커밋) 이력 조회.
 */
export function useVersions(videoId: number | undefined) {
  return useQuery({
    queryKey: videoId !== undefined ? VERSION_KEYS.history(videoId) : VERSION_KEYS.all,
    queryFn: () => listVersions(videoId as number),
    enabled: videoId !== undefined,
  });
}
