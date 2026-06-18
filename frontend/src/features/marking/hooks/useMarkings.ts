import { useMutation, useQueryClient } from '@tanstack/react-query';
import { ASSIGNMENT_KEYS, MARKING_KEYS, TASK_BOARD_KEYS } from '@/lib/queryKeys';
import { createMarking } from '../api';
import type { MarkingRequest, MarkingResponse } from '../types';

export function useCreateMarking(
  rawSn: number | undefined,
  options?: {
    onSuccess?: (data: MarkingResponse) => void;
    onError?: (err: unknown) => void;
  },
) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: MarkingRequest) => createMarking(rawSn as number, body),
    onSuccess: (data) => {
      qc.invalidateQueries({ queryKey: MARKING_KEYS.all });
      qc.invalidateQueries({ queryKey: TASK_BOARD_KEYS.all });
      qc.invalidateQueries({ queryKey: ASSIGNMENT_KEYS.all });
      options?.onSuccess?.(data);
    },
    onError: (err) => {
      options?.onError?.(err);
    },
  });
}
