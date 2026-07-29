import { keepPreviousData, useQuery } from '@tanstack/react-query';

import { TASK_BOARD_KEYS } from '@/lib/queryKeys';

import { listTaskBoard } from '../api';
import type { TaskBoardParams } from '../types';

/**
 * SCR-TASK-001 REVIEWER 통합 작업 목록 훅 — BE /v1/tasks/board.
 *
 * 처리 완료 영상 + (left-join) LABELER/REVIEWER 배정을 BE 단일 엔드포인트에서 페이징 응답한다.
 * 기존 REVIEWER 시각이 `useVideos({size:999}) + useTasks({size:999})` 두 번 호출 후
 * 클라이언트에서 left-join 하던 로직을 BE 로 이관한 결과를 그대로 사용한다.
 *
 * 옵션 `enabled` 는 호출자(TaskListPage) 가 REVIEWER 여부로 토글한다.
 */
export function useTaskBoard(
  params: TaskBoardParams,
  options?: { enabled?: boolean },
) {
  return useQuery({
    queryKey: TASK_BOARD_KEYS.list(params as Record<string, unknown>),
    queryFn: () => listTaskBoard(params),
    enabled: options?.enabled ?? true,
    // 필터/페이지 전환 시 이전 목록 유지 — 전체 스켈레톤 리렌더와, 늦게 도착한 이전 응답이
    // 깜빡였다 바뀌는 flicker 를 막는다(useTasks 와 동일 정책).
    placeholderData: keepPreviousData,
  });
}
