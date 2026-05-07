import { useQuery } from '@tanstack/react-query';

import { REVIEW_KEYS } from '@/lib/queryKeys';

import { getReview } from '../api';

/**
 * 검수 단건 상세 조회.
 */
export function useReview(reviewId: number | undefined) {
  return useQuery({
    queryKey: REVIEW_KEYS.detail(reviewId ?? -1),
    queryFn: () => getReview(reviewId as number),
    enabled: typeof reviewId === 'number' && reviewId > 0,
  });
}
