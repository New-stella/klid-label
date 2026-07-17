// Phase 5 — 포털 업로드 자산 삭제 mutation. 성공 시 목록 무효화.
import { useCallback } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';

import { PORTAL_KEYS } from '@/lib/queryKeys';

import { deleteUpload } from '../api';

export interface UseDeleteUploadResult {
  deleteAsync: (uldSn: number) => Promise<void>;
  isPending: boolean;
  error: unknown;
}

export function useDeleteUpload(): UseDeleteUploadResult {
  const qc = useQueryClient();

  const mutation = useMutation({
    mutationFn: (uldSn: number) => deleteUpload(uldSn),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: PORTAL_KEYS.all });
    },
  });

  const deleteAsync = useCallback(
    (uldSn: number) => mutation.mutateAsync(uldSn),
    [mutation],
  );

  return { deleteAsync, isPending: mutation.isPending, error: mutation.error };
}
