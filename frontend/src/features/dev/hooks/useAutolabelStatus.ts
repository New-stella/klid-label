import { useQuery } from '@tanstack/react-query';

import { getVideo } from '@/features/video/api';
import type { VideoDetail } from '@/features/video/types';
import { VIDEO_KEYS } from '@/lib/queryKeys';

/**
 * 오토라벨 파이프라인 진행 상태 polling 훅.
 *
 * 별도 status endpoint 가 없으므로 기존 `GET /videos/{id}` 를 2초 간격으로 polling 한다.
 * 영상 상세의 `status` / `stages` / `framePreviews` 변화로 파이프라인 진행 상황을 가시화.
 *
 * @param rawSn 업로드 응답에서 받은 RAW_SN. null 이면 polling 비활성.
 * @param enabled 외부에서 polling 중단 (예: COMPLETED 도달 시) 토글.
 */
export function useAutolabelStatus(
  rawSn: number | null,
  enabled: boolean = true,
) {
  return useQuery<VideoDetail>({
    queryKey: VIDEO_KEYS.detail(rawSn ?? -1),
    queryFn: () => getVideo(rawSn as number),
    enabled: enabled && rawSn !== null && rawSn > 0,
    refetchInterval: enabled ? 2000 : false,
    // 파이프라인 진행 중에는 매 polling 마다 최신값을 받기 위해 stale time 0.
    staleTime: 0,
  });
}
