import { useQuery } from '@tanstack/react-query';

import { AUTOLABEL_KEYS } from '@/lib/queryKeys';

import { getVideoLabels } from '../api';

export function useVideoLabels(videoId: number | string | undefined) {
  return useQuery({
    queryKey: AUTOLABEL_KEYS.byVideo(Number(videoId ?? 0)),
    queryFn: () => getVideoLabels(videoId!),
    enabled: videoId != null,
    retry: false,
  });
}
