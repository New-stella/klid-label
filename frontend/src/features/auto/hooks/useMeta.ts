import { useQuery } from '@tanstack/react-query';

import { META_KEYS } from '@/lib/queryKeys';

import { getMeta } from '../api';

export function useMeta(srcSn: number | undefined) {
  return useQuery({
    queryKey: srcSn !== undefined ? META_KEYS.byVideo(srcSn) : META_KEYS.all,
    queryFn: () => getMeta(srcSn as number),
    enabled: srcSn !== undefined,
  });
}
