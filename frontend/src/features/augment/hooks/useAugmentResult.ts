import { useQuery } from '@tanstack/react-query';

import { AUGMENT_KEYS } from '@/lib/queryKeys';

import { getAugmentResult } from '../api';

/**
 * 증강 결과 단건 조회 (영상별 섹션 + 유형 탭 + FrameGrid12).
 */
export function useAugmentResult(jobId: number | undefined) {
  return useQuery({
    queryKey:
      jobId !== undefined ? AUGMENT_KEYS.detail(jobId) : AUGMENT_KEYS.all,
    queryFn: () => getAugmentResult(jobId as number),
    enabled: jobId !== undefined,
  });
}
