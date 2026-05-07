import { useQuery } from '@tanstack/react-query';

import { STAT_KEYS } from '@/lib/queryKeys';

import { getDashboardSummary } from '../api';

export function useDashboardSummary() {
  return useQuery({
    queryKey: STAT_KEYS.overall(),
    queryFn: getDashboardSummary,
    staleTime: 60_000,
  });
}
