import { keepPreviousData, useQuery } from '@tanstack/react-query';

import { AUGMENT_KEYS } from '@/lib/queryKeys';

import { getAugmentResult } from '../api';
import type { GetAugmentResultParams } from '../types';

/**
 * 증강 결과 단건 조회 (영상별 섹션 + 유형 탭 + FrameGrid12).
 *
 * `params.page`/`params.size` 는 프레임 쌍 페이징. 페이지 전환 시 이전 데이터를 유지해
 * (keepPreviousData) 페이저가 사라졌다 다시 나타나는 깜빡임을 막는다.
 */
export function useAugmentResult(
  jobId: number | undefined,
  params: GetAugmentResultParams = {},
) {
  return useQuery({
    queryKey:
      jobId !== undefined
        ? AUGMENT_KEYS.detail(jobId, { ...params })
        : AUGMENT_KEYS.all,
    queryFn: () => getAugmentResult(jobId as number, params),
    enabled: jobId !== undefined,
    placeholderData: keepPreviousData,
  });
}
