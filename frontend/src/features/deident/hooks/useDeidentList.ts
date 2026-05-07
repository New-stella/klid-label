import { useQuery } from '@tanstack/react-query';

import { DEIDENT_KEYS } from '@/lib/queryKeys';

import { listDeidentResults } from '../api';
import type { DeidentListParams } from '../types';

export function useDeidentList(params: DeidentListParams) {
  return useQuery({
    queryKey: DEIDENT_KEYS.list(params as Record<string, unknown>),
    queryFn: () => listDeidentResults(params),
  });
}
