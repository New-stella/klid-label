import { useMutation, useQueryClient } from '@tanstack/react-query';

import { VERSION_KEYS } from '@/lib/queryKeys';

import { commitLabels } from '../api';

export interface UseCommitLabelsOptions {
  onSuccess?: () => void;
  onError?: (err: unknown) => void;
}

/**
 * Gitea 커밋 트리거 mutation.
 */
export function useCommitLabels(srcSn: number | undefined, options: UseCommitLabelsOptions = {}) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (message?: string) => {
      if (srcSn === undefined) {
        return Promise.reject(new Error('srcSn is required'));
      }
      return commitLabels(srcSn, message);
    },
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: VERSION_KEYS.all });
      options.onSuccess?.();
    },
    onError: options.onError,
  });
}
