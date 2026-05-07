import { useQuery } from '@tanstack/react-query';

import { LABEL_KEYS } from '@/lib/queryKeys';

import { getLabels } from '../api';

/**
 * 프레임의 라벨 목록 조회.
 */
export function useLabels(srcSn: number | undefined) {
  return useQuery({
    queryKey: srcSn !== undefined ? LABEL_KEYS.byFrame(srcSn, 0) : LABEL_KEYS.all,
    queryFn: () => getLabels(srcSn as number),
    enabled: srcSn !== undefined,
  });
}
