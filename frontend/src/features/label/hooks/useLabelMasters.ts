// Phase 7 — 라벨 마스터 조회 훅 (TanStack Query).
//
// LabelSidebar / ObjectAttributePanel / OverlayLayer 가 공유 사용.
// staleTime 5분 — 라벨 마스터는 변경 빈도 낮은 코드성 데이터.

import { useQuery } from '@tanstack/react-query';

import { fetchLabelMasters, type LabelMaster } from '../api/labelMaster';

export const LABEL_MASTER_KEYS = {
  all: ['label-masters'] as const,
  list: () => [...LABEL_MASTER_KEYS.all, 'list'] as const,
};

export function useLabelMasters() {
  return useQuery<LabelMaster[]>({
    queryKey: LABEL_MASTER_KEYS.list(),
    queryFn: fetchLabelMasters,
    staleTime: 5 * 60 * 1000,
  });
}
