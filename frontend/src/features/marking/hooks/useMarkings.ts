import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ASSIGNMENT_KEYS, MARKING_KEYS, TASK_BOARD_KEYS } from '@/lib/queryKeys';
import { createMarking, deleteMarking, getMarkings } from '../api';
import type { MarkingRequest, MarkingResponse } from '../types';

export function useMarkings(rawSn: number | undefined) {
  return useQuery({
    queryKey: rawSn !== undefined ? MARKING_KEYS.byVideo(rawSn) : MARKING_KEYS.all,
    queryFn: () => getMarkings(rawSn as number),
    enabled: rawSn !== undefined,
  });
}

export function useCreateMarking(rawSn: number | undefined, options?: { onSuccess?: (data: MarkingResponse) => void }) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: MarkingRequest) => createMarking(rawSn as number, body),
    onSuccess: (data) => {
      qc.invalidateQueries({ queryKey: MARKING_KEYS.all });
      qc.invalidateQueries({ queryKey: TASK_BOARD_KEYS.all });
      qc.invalidateQueries({ queryKey: ASSIGNMENT_KEYS.all });
      options?.onSuccess?.(data);
    },
  });
}

export function useDeleteMarking(rawSn: number | undefined, options?: { onSuccess?: () => void }) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (markingSn: number) => deleteMarking(rawSn as number, markingSn),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: MARKING_KEYS.all });
      options?.onSuccess?.();
    },
  });
}
