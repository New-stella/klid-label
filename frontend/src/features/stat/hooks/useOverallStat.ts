import { useQuery } from '@tanstack/react-query';

import { STAT_KEYS } from '@/lib/queryKeys';

import { getOverallStats } from '../api';

export function useOverallStat() {
  return useQuery({
    queryKey: STAT_KEYS.overall(),
    queryFn: getOverallStats,
    staleTime: 60_000,
  });
}
