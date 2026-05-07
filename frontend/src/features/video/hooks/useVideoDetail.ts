import { useQuery } from '@tanstack/react-query';

import { VIDEO_KEYS } from '@/lib/queryKeys';

import { getVideo } from '../api';

export function useVideoDetail(id: number | null) {
  return useQuery({
    queryKey: VIDEO_KEYS.detail(id ?? -1),
    queryFn: () => getVideo(id as number),
    enabled: id !== null && id > 0,
  });
}
