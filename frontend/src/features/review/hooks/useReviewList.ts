import { keepPreviousData, useQuery } from '@tanstack/react-query';

import { REVIEW_KEYS } from '@/lib/queryKeys';

import { listReviews } from '../api';
import type { ReviewListParams } from '../types';

/**
 * 검수 대기 목록 조회 (REVIEWER 전용).
 */
export function useReviewList(params: ReviewListParams) {
  return useQuery({
    queryKey: REVIEW_KEYS.pending(params as Record<string, unknown>),
    queryFn: () => listReviews(params),
    // 페이지/필터 전환 시 이전 목록 유지(빈 상태 깜빡임 방지).
    placeholderData: keepPreviousData,
  });
}
