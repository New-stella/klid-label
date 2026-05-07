import { useQuery } from '@tanstack/react-query';

import { STAT_KEYS } from '@/lib/queryKeys';

import { getWorkerDashboard } from '../api';
import type { StatPeriod } from '../types';

export function useWorkerStat(userId: number, period: StatPeriod) {
  return useQuery({
    queryKey: [...STAT_KEYS.worker(userId), period],
    queryFn: () => getWorkerDashboard(period),
    staleTime: 60_000,
  });
}
