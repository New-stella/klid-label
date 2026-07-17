import { keepPreviousData, useQuery } from '@tanstack/react-query';

import { ASSIGNMENT_KEYS } from '@/lib/queryKeys';

import { listTasks } from '../api';
import type { TaskListParams } from '../types';

/**
 * WORKER 시각 본인 배정 목록 훅 — BE /v1/assignments 페이징.
 *
 * `options.enabled` 로 호출을 게이트할 수 있다. REVIEWER 시각은 useTaskBoard 만 사용하므로
 * TaskListPage 가 `enabled: !isReviewer` 로 호출을 차단하여 불필요한 /assignments 요청을 막는다.
 */
export function useTasks(
  params: TaskListParams,
  options?: { enabled?: boolean },
) {
  return useQuery({
    queryKey: ASSIGNMENT_KEYS.list(params as Record<string, unknown>),
    queryFn: () => listTasks(params),
    enabled: options?.enabled ?? true,
    // 페이지 전환 시 이전 목록 유지(빈 상태 깜빡임 방지).
    placeholderData: keepPreviousData,
  });
}
