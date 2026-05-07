import { useMutation, useQueryClient } from '@tanstack/react-query';

import { ASSIGNMENT_KEYS, VIDEO_KEYS } from '@/lib/queryKeys';

import { assignTask } from '../api';
import type { AssignTaskRequest, Assignment } from '../types';

export interface UseAssignTaskOptions {
  onSuccess?: (data: Assignment) => void;
  onError?: (err: unknown) => void;
}

/**
 * 배정 mutation. 성공 시 assignments/videos 쿼리 무효화.
 */
export function useAssignTask(options: UseAssignTaskOptions = {}) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: AssignTaskRequest) => assignTask(body),
    onSuccess: (data) => {
      qc.invalidateQueries({ queryKey: ASSIGNMENT_KEYS.all });
      qc.invalidateQueries({ queryKey: VIDEO_KEYS.all });
      options.onSuccess?.(data);
    },
    onError: options.onError,
  });
}
