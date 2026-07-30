import { useQuery } from '@tanstack/react-query';

import { TASK_BOARD_KEYS } from '@/lib/queryKeys';

import { getTaskBoardEventTypes } from '../api';
import type { EventTypeOptionsResponse, TaskBoardEventTypeParams } from '../types';

/** BE 에 캐시를 두지 않기로 했으므로 FE 가 호출 빈도를 억제한다(옵션은 사실상 불변). */
const EVENT_TYPE_OPTIONS_STALE_MS = 5 * 60 * 1000;

const EMPTY_OPTIONS: EventTypeOptionsResponse = { items: [], truncated: false };

/**
 * 이벤트유형 셀렉트 옵션 훅 — BE /v1/tasks/board/event-types.
 *
 * 응답이 `{ items, truncated }` **객체**이므로 소비측이 배열로 오인해 `.map` 하지 않도록
 * 이 훅이 `items` / `truncated` 를 분해해 돌려준다(실패 시 빈 옵션 폴백 — 목록은 계속 보여야 한다).
 */
export function useTaskBoardEventTypes(
  params: TaskBoardEventTypeParams,
  options?: { enabled?: boolean },
) {
  const query = useQuery({
    queryKey: TASK_BOARD_KEYS.eventTypes(params as Record<string, unknown>),
    queryFn: () => getTaskBoardEventTypes(params),
    enabled: options?.enabled ?? true,
    staleTime: EVENT_TYPE_OPTIONS_STALE_MS,
  });

  const data = query.data ?? EMPTY_OPTIONS;
  return {
    ...query,
    items: data.items,
    truncated: data.truncated,
  };
}
