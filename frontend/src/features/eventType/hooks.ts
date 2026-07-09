// 이벤트 타입 조회 훅 — TanStack Query v5.
//
// 이벤트 타입은 near-immutable(관제 마스터 기반, 사실상 불변)이므로 staleTime: Infinity 로
// 한 세션 1회만 페치한다. 커스텀 훅으로 useQuery 를 감싼다 (state-management 규칙).

import { useQuery } from '@tanstack/react-query';

import { EVENT_TYPE_KEYS } from '@/lib/queryKeys';

import { getEventTypeLabels, getEventTypes } from './api';

/** 필터 드롭다운용 카테고리 옵션 9종. */
export function useEventTypes() {
  return useQuery({
    queryKey: EVENT_TYPE_KEYS.options(),
    queryFn: getEventTypes,
    staleTime: Infinity,
  });
}

/** 이벤트 코드 → 한글 카테고리명 맵. */
export function useEventTypeLabels() {
  return useQuery({
    queryKey: EVENT_TYPE_KEYS.labels(),
    queryFn: getEventTypeLabels,
    staleTime: Infinity,
  });
}
