import { useQuery } from '@tanstack/react-query';

import { STAT_KEYS } from '@/lib/queryKeys';

import { getWorkerDashboard } from '../api';

export function useWorkerStat(workerId?: number | string) {
  return useQuery({
    queryKey: [...STAT_KEYS.worker(workerId ?? 'self')],
    queryFn: () => getWorkerDashboard(workerId),
    staleTime: 60_000,
  });
}
