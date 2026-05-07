import { useQuery } from '@tanstack/react-query';

import { VIDEO_KEYS } from '@/lib/queryKeys';

import { getBatchStatus } from '../api';

/** 5초 폴링으로 배치 처리 현황 자동 갱신 */
export const BATCH_STATUS_POLL_INTERVAL_MS = 5000;

export function useBatchStatus() {
  return useQuery({
    queryKey: VIDEO_KEYS.status(),
    queryFn: getBatchStatus,
    refetchInterval: BATCH_STATUS_POLL_INTERVAL_MS,
    refetchIntervalInBackground: false,
  });
}
