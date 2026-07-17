import { keepPreviousData, useQuery } from '@tanstack/react-query';

import { AUGMENT_KEYS } from '@/lib/queryKeys';

import { listAugmentJobs } from '../api';
import type { ListAugmentJobsParams } from '../types';

/**
 * 증강 잡 이력 조회 — 5초 폴링.
 * UI/UX §4-12: 잡 카드 6건 그리드 / 5초 폴링으로 상태 변화 반영.
 */
export function useAugmentJobs(params: ListAugmentJobsParams = {}) {
  return useQuery({
    queryKey: AUGMENT_KEYS.list(params as Record<string, unknown>),
    queryFn: () => listAugmentJobs(params),
    refetchInterval: 5000,
    // 페이지/필터 전환 시 이전 잡 카드 유지(빈 상태 깜빡임 방지).
    placeholderData: keepPreviousData,
  });
}
