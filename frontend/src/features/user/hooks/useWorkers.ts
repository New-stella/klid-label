import { useQuery } from '@tanstack/react-query';

import { USER_KEYS } from '@/lib/queryKeys';

import { listWorkers } from '../api';

interface UseWorkersOptions {
  /**
   * react-query 의 enabled 와 동일. 기본값 true.
   * REVIEWER 전용 API (/users/workers) 라 WORKER 화면에서는 false 로 막아 403 스팸을 방지한다.
   */
  enabled?: boolean;
}

export function useWorkers(options: UseWorkersOptions = {}) {
  const { enabled = true } = options;
  return useQuery({
    queryKey: [...USER_KEYS.all, 'workers'] as const,
    queryFn: listWorkers,
    enabled,
  });
}
