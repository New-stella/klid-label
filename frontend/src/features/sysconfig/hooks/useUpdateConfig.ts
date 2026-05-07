import { useMutation, useQueryClient } from '@tanstack/react-query';

import { SYSCONFIG_KEYS } from '@/lib/queryKeys';

import { updateConfig } from '../api';
import type { ConfigItem, ConfigUpdateRequest } from '../types';

export interface UseUpdateConfigOptions {
  onSuccess?: (data: ConfigItem) => void;
  onError?: (err: unknown) => void;
}

export function useUpdateConfig(options: UseUpdateConfigOptions = {}) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: ConfigUpdateRequest) => updateConfig(body),
    onSuccess: (data) => {
      qc.invalidateQueries({ queryKey: SYSCONFIG_KEYS.all });
      options.onSuccess?.(data);
    },
    onError: options.onError,
  });
}
