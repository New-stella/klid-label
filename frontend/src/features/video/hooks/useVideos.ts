import { keepPreviousData, useQuery } from '@tanstack/react-query';

import { VIDEO_KEYS } from '@/lib/queryKeys';

import { listVideos } from '../api';
import type { VideoListParams } from '../types';

export function useVideos(
  params: VideoListParams,
  options?: { enabled?: boolean },
) {
  return useQuery({
    queryKey: VIDEO_KEYS.list(params as Record<string, unknown>),
    queryFn: () => listVideos(params),
    enabled: options?.enabled,
    // 페이지/필터 전환 시 이전 데이터를 유지해 목록이 빈 상태로 깜빡이지 않게 한다(isFetching 로 갱신 표시).
    placeholderData: keepPreviousData,
  });
}
