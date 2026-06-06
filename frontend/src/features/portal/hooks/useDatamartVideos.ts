import { useQuery } from '@tanstack/react-query';

import { PORTAL_KEYS } from '@/lib/queryKeys';

import { listDatamartVideos } from '../api';

/**
 * Phase B — 포털 홈 데이터마트 영상 목록 훅.
 *
 * BE: GET /v1/portal/datamart/videos — 데이터마트 노출(검수 완료=APPROVED) 영상만 페이징.
 * 프레임 0건 영상은 BE 가 제외하므로 firstSrcSn 은 항상 존재 → 라벨링 진입에 그대로 사용.
 */
export function useDatamartVideos(params: { page?: number; size?: number } = {}) {
  return useQuery({
    queryKey: PORTAL_KEYS.datamartVideos(params as Record<string, unknown>),
    queryFn: () => listDatamartVideos(params),
  });
}
