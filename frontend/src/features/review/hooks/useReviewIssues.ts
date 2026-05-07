import { useQuery } from '@tanstack/react-query';

import { REVIEW_KEYS } from '@/lib/queryKeys';

import { listIssues } from '../api';

/**
 * 검수 이슈 목록 조회 (프레임별 카드 누적용).
 */
export function useReviewIssues(reviewId: number | undefined) {
  return useQuery({
    queryKey: [...REVIEW_KEYS.detail(reviewId ?? -1), 'issues'],
    queryFn: () => listIssues(reviewId as number),
    enabled: typeof reviewId === 'number' && reviewId > 0,
  });
}
