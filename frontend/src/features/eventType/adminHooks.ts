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
 * ★성공 응답에는 갱신된 표시명과 그 <b>출처</b>가 함께 실려 오므로 <b>관리 목록을 다시 불러오지
 * 않고 그 행만 응답값으로 갱신</b>한다(사양 SCREEN-038). 재조회하면 같은 사실을 두 번 받아오는
 * 왕복이 되고, 그 사이 목록이 잠깐 옛 값으로 남는다.
 *
 * 반면 <b>필터 옵션·라벨맵은 무효화</b>한다 — 표시명이 갈리면 옵션의 접기 결과가 달라지고
 * 수집여부는 드롭다운 노출을 가른다(BE 캐시는 커밋 후 evict 된다).
 *
 * ⚠ `EVENT_TYPE_KEYS.all` 을 통째로 무효화하면 <b>접두 일치로 ADMIN_KEY 까지 걸려</b> 위의
 * "재조회하지 않는다"가 무너진다 — 그래서 options()·labels() 를 각각 지정한다.
 *
 * @design SCREEN-038, API-186
 */
export function useUpdateEventTypeAdmin() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ evntTypeCd, body }: { evntTypeCd: string; body: EventTypeAdminUpdate }) =>
      updateEventTypeAdmin(evntTypeCd, body),
    onSuccess: (updated) => {
      queryClient.setQueryData<EventTypeAdminItem[]>(ADMIN_KEY, (prev) =>
        // 캐시가 비어 있으면(직접 진입 전) 만들어 두지 않는다 — 목록 전체를 알 수 없다.
        prev?.map((row) => (row.evntTypeCd === updated.evntTypeCd ? updated : row)),
      );
      void queryClient.invalidateQueries({ queryKey: EVENT_TYPE_KEYS.options() });
      void queryClient.invalidateQueries({ queryKey: EVENT_TYPE_KEYS.labels() });
    },
  });
}
