import { useMutation, useQueryClient } from '@tanstack/react-query';

import { ASSIGNMENT_KEYS, TASK_BOARD_KEYS, VIDEO_KEYS } from '@/lib/queryKeys';

import { assignTask } from '../api';
import type { AssignTaskRequest, Assignment } from '../types';

export interface UseAssignTaskOptions {
  onSuccess?: (data: Assignment) => void;
  onError?: (err: unknown) => void;
}

/**
 * 배정 mutation. 성공 시 다음 캐시를 무효화한다:
 *  - ASSIGNMENT_KEYS.all: 배정 이력/단건 조회
 *  - VIDEO_KEYS.all: 영상 목록(미배정 표시 등)
 *  - TASK_BOARD_KEYS.all: SCR-TASK-001 REVIEWER 통합 작업 목록(/v1/tasks/board)
 *    — 누락 시 REVIEWER가 배정 후 작업 목록에 옛 데이터(UNASSIGNED) 가 잔존한다.
 */
export function useAssignTask(options: UseAssignTaskOptions = {}) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: AssignTaskRequest) => assignTask(body),
    onSuccess: (data) => {
      qc.invalidateQueries({ queryKey: ASSIGNMENT_KEYS.all });
      qc.invalidateQueries({ queryKey: VIDEO_KEYS.all });
      qc.invalidateQueries({ queryKey: TASK_BOARD_KEYS.all });
      options.onSuccess?.(data);
    },
    onError: options.onError,
  });
}
