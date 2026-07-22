// Phase 4 — event_annotation 조회 훅 (TanStack Query).
//
// 서버 상태는 TanStack Query 로 관리(state-management.md). 컴포넌트는 이 커스텀 훅만 사용.
// queryKey 는 EVENT_ANNOTATION_KEYS factory 로 영상(rawSn) 단위 분리.

import { useQuery } from '@tanstack/react-query';

import { EVENT_ANNOTATION_KEYS } from '@/lib/queryKeys';

import { getEventAnnotation, type EventAnnotationInfo } from '../api/eventAnnotation';

/** 현재 영상(rawSn)의 event_annotation 조회. rawSn 없으면 비활성. */
export function useEventAnnotation(rawSn: number | undefined) {
  return useQuery<EventAnnotationInfo>({
    queryKey:
      rawSn !== undefined
        ? EVENT_ANNOTATION_KEYS.byVideo(rawSn)
        : EVENT_ANNOTATION_KEYS.all,
    queryFn: () => getEventAnnotation(rawSn as number),
    enabled: rawSn !== undefined,
    // 아직 생성 전이면 BE 404 — 재시도 없이 빈 폼으로 시작.
    retry: false,
  });
}
