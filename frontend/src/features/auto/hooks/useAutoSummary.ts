import { useQuery } from '@tanstack/react-query';

import { AUTOLABEL_KEYS } from '@/lib/queryKeys';

import { getAutoLabelSummary } from '../api';

export function useAutoSummary(videoId: number | undefined) {
  return useQuery({
    queryKey:
      videoId !== undefined ? AUTOLABEL_KEYS.byVideo(videoId) : AUTOLABEL_KEYS.all,
    queryFn: () => getAutoLabelSummary(videoId as number),
    enabled: videoId !== undefined,
  });
}
