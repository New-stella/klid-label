// 이벤트유형 관리 훅 — TanStack Query v5 (커스텀 훅으로 감싼다, state-management 규칙).

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import { EVENT_TYPE_KEYS } from '@/lib/queryKeys';

import {
  getEventTypeAdminList,
  updateEventTypeAdmin,
  type EventTypeAdminItem,
  type EventTypeAdminUpdate,
} from './adminApi';

const ADMIN_KEY = [...EVENT_TYPE_KEYS.all, 'admin'] as const;

/** 관리 화면 목록 — 필터 옵션과 달리 비수집·제외 대분류도 포함한다. */
export function useEventTypeAdminList() {
  return useQuery<EventTypeAdminItem[]>({
    queryKey: ADMIN_KEY,
    queryFn: getEventTypeAdminList,
    staleTime: 30 * 1000,
  });
}

/**
 * 이벤트유형 수정.
 *
 * 성공 시 관리 목록과 <필터 옵션·라벨맵>을 함께 무효화한다 — 표시명·수집여부가 바뀌면
 * 드롭다운·목록 라벨도 즉시 달라져야 한다(BE 캐시는 커밋 후 evict 된다).
 */
export function useUpdateEventTypeAdmin() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ evntTypeCd, body }: { evntTypeCd: string; body: EventTypeAdminUpdate }) =>
      updateEventTypeAdmin(evntTypeCd, body),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ADMIN_KEY });
      void queryClient.invalidateQueries({ queryKey: EVENT_TYPE_KEYS.all });
    },
  });
}
