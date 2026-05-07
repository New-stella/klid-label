import { useQuery } from '@tanstack/react-query';

import { SYSCONFIG_KEYS } from '@/lib/queryKeys';

import { listPresets } from '../api';

const PRESETS_KEY = [...SYSCONFIG_KEYS.presets(), 'list'] as const;

export function usePresets() {
  return useQuery({
    queryKey: PRESETS_KEY,
    queryFn: listPresets,
    staleTime: 30_000,
  });
}

export const PRESETS_QUERY_KEY = PRESETS_KEY;
