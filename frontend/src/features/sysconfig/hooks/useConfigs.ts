import { useQuery } from '@tanstack/react-query';

import { SYSCONFIG_KEYS } from '@/lib/queryKeys';

import { getConfigs } from '../api';
import type { ConfigItem, ConfigMap } from '../types';

export function useConfigs() {
  return useQuery({
    queryKey: SYSCONFIG_KEYS.presets(),
    queryFn: getConfigs,
    select: (items: ConfigItem[]): ConfigMap =>
      items.reduce<ConfigMap>((acc, item) => {
        acc[String(item.key)] = item.value;
        return acc;
      }, {}),
  });
}
