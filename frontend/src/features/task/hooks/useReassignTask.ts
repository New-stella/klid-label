import { useMutation, useQueryClient } from '@tanstack/react-query';

import { ASSIGNMENT_KEYS } from '@/lib/queryKeys';

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
 */
export function useReassignTask(options: UseReassignTaskOptions = {}) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, body }: ReassignArgs) => reassignTask(id, body),
    onSuccess: (data) => {
      qc.invalidateQueries({ queryKey: ASSIGNMENT_KEYS.all });
      options.onSuccess?.(data);
    },
    onError: options.onError,
  });
}
