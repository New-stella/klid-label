import { useQuery } from '@tanstack/react-query';

import { REVIEW_KEYS } from '@/lib/queryKeys';

import { getReviewFrames } from '../api';
import type { FrameList } from '../types';

/**
 * 검수 화면 프레임 목록 + 라벨 조회.
 * BE: GET /api/v1/reviews/{videoId}/frames (Phase 1 신설)
 *
 * staleTime 30초 — 동일 영상 내 빠른 재방문 시 캐시 재사용.
 */
export function useReviewFrames(videoId: number | undefined) {
  return useQuery<FrameList>({
    queryKey: REVIEW_KEYS.frames(videoId ?? 0),
    queryFn: () => getReviewFrames(videoId as number),
    enabled: typeof videoId === 'number' && videoId > 0,
    staleTime: 30_000,
  });
}
