import { useQuery } from '@tanstack/react-query';

import { DEIDENT_KEYS } from '@/lib/queryKeys';

import { getDeidentDetail } from '../api';

export function useDeidentDetail(videoId: number | undefined) {
  return useQuery({
    queryKey:
      videoId !== undefined ? DEIDENT_KEYS.detail(videoId) : DEIDENT_KEYS.all,
    queryFn: () => getDeidentDetail(videoId as number),
    enabled: videoId !== undefined,
  });
}
