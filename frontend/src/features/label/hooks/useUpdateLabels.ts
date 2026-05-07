import { useMutation, useQueryClient } from '@tanstack/react-query';

import { LABEL_KEYS } from '@/lib/queryKeys';

import { putLabels } from '../api';
import type { Label } from '../types';

export interface UseUpdateLabelsOptions {
  onSuccess?: () => void;
  onError?: (err: unknown) => void;
}

/**
 * 라벨 일괄 PUT mutation. 성공 시 LABEL_KEYS 무효화.
 */
export function useUpdateLabels(srcSn: number | undefined, options: UseUpdateLabelsOptions = {}) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (labels: Label[]) => {
      if (srcSn === undefined) {
        return Promise.reject(new Error('srcSn is required'));
      }
      return putLabels(srcSn, labels);
    },
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: LABEL_KEYS.all });
      options.onSuccess?.();
    },
    onError: options.onError,
  });
}
