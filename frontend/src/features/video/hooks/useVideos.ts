import { useQuery } from '@tanstack/react-query';

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
  });
}
