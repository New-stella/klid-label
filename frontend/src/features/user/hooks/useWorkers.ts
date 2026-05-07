import { useQuery } from '@tanstack/react-query';

import { USER_KEYS } from '@/lib/queryKeys';

import { listWorkers } from '../api';

export function useWorkers() {
  return useQuery({
    queryKey: [...USER_KEYS.all, 'workers'] as const,
    queryFn: listWorkers,
  });
}
