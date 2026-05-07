import { useMutation, useQueryClient } from '@tanstack/react-query';

import { META_KEYS } from '@/lib/queryKeys';

import { updateMeta } from '../api';
import type { FrameMetaUpdateRequest } from '../types';

export interface UseUpdateMetaOptions {
  onSuccess?: () => void;
  onError?: (err: unknown) => void;
}

/**
 * 시계열 메타 수정 mutation.
 * 성공 시 META_KEYS 무효화.
 */
export function useUpdateMeta(srcSn: number | undefined, options: UseUpdateMetaOptions = {}) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: FrameMetaUpdateRequest) => {
      if (srcSn === undefined) {
        return Promise.reject(new Error('srcSn is required'));
      }
      return updateMeta(srcSn, body);
    },
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: META_KEYS.all });
      options.onSuccess?.();
    },
    onError: options.onError,
  });
}
