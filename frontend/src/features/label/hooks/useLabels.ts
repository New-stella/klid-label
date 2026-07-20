import { keepPreviousData, useQuery } from '@tanstack/react-query';

import { LABEL_KEYS } from '@/lib/queryKeys';
import { getPortalLabels } from '@/features/portal/api';

import { getLabels } from '../api';

/**
 * 프레임의 라벨 목록 조회.
 *
 * placeholderData: keepPreviousData — 프레임 전환 시 페이지 깜빡임/재생 끊김 방지.
 *   - srcSn 변경 시 isLoading 은 false 유지(첫 fetch 만 true), isFetching 만 true
 *   - 이전 data 가 유지되므로 LabelingPage 의 `if (isLoading) return <Spinner>` 분기를
 *     타지 않아 DarkFrameSlider 가 unmount 되지 않음 → 재생 인터벌(setInterval) 유지
 *
 * R16 — portalMode=true 면 내부 전용 /frames/{id}/labels(403) 대신 포털 전용
 *   /portal/frames/{id}/labels 로 Load (datamart 원본 + 본인 user-label 병합).
 */
export function useLabels(srcSn: number | undefined, portalMode = false) {
  return useQuery({
    queryKey:
      srcSn !== undefined
        ? [...LABEL_KEYS.byFrame(srcSn, 0), portalMode ? 'portal' : 'internal']
        : LABEL_KEYS.all,
    queryFn: () => (portalMode ? getPortalLabels(srcSn as number) : getLabels(srcSn as number)),
    enabled: srcSn !== undefined,
    placeholderData: keepPreviousData,
    // 미저장 병합/편집 보호(HIGH #2) — 창 포커스 복귀 시 자동 refetch 로 작업본을 덮어쓰지 않는다.
    refetchOnWindowFocus: false,
    // 짧은 staleTime 으로 동일 프레임 재진입 시 불필요한 재조회를 억제(작업 중 깜빡임/덮어쓰기 방지).
    staleTime: 30_000,
  });
}
