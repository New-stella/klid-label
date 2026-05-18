import { useMutation, useQueryClient } from '@tanstack/react-query';

import { ASSIGNMENT_KEYS, TASK_BOARD_KEYS, VIDEO_KEYS } from '@/lib/queryKeys';

import { reassignTask } from '../api';
import type { Assignment, ReassignTaskRequest } from '../types';

export interface UseReassignTaskOptions {
  onSuccess?: (data: Assignment) => void;
  onError?: (err: unknown) => void;
}

export interface ReassignArgs {
  id: number;
  body: ReassignTaskRequest;
}

/**
 * 재배정 mutation — PATCH /assignments/{id} (BE plan 정합).
 * 성공 시 다음 캐시를 무효화한다:
 *  - ASSIGNMENT_KEYS.all: 배정 이력/단건 조회
 *  - VIDEO_KEYS.all: 영상 목록
 *  - TASK_BOARD_KEYS.all: SCR-TASK-001 REVIEWER 통합 작업 목록(/v1/tasks/board)
 *    — 누락 시 REVIEWER가 재배정 후 작업 목록의 작업자 컬럼이 옛 값으로 표시된다.
 */
export function useReassignTask(options: UseReassignTaskOptions = {}) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, body }: ReassignArgs) => reassignTask(id, body),
    onSuccess: (data) => {
      qc.invalidateQueries({ queryKey: ASSIGNMENT_KEYS.all });
      qc.invalidateQueries({ queryKey: VIDEO_KEYS.all });
      qc.invalidateQueries({ queryKey: TASK_BOARD_KEYS.all });
      options.onSuccess?.(data);
    },
    onError: options.onError,
  });
}
