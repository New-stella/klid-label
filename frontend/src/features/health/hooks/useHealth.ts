import { useQuery } from '@tanstack/react-query';

import { getHealth, HEALTH_POLL_INTERVAL_MS } from '../api';

const HEALTH_KEY = ['health', 'status'] as const;

export function useHealth() {
  return useQuery({
    queryKey: HEALTH_KEY,
    queryFn: getHealth,
    refetchInterval: HEALTH_POLL_INTERVAL_MS,
    refetchIntervalInBackground: false,
  });
}
