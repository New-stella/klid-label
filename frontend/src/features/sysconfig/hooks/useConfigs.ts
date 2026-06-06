import { useQuery } from '@tanstack/react-query';

import { SYSCONFIG_KEYS } from '@/lib/queryKeys';

import { getConfigs } from '../api';
import type { ConfigItem, ConfigMap } from '../types';

export function useConfigs() {
  return useQuery({
    queryKey: SYSCONFIG_KEYS.presets(),
    queryFn: getConfigs,
    // R3-2: BE 는 configKey / configVl(문자열) 을 반환한다.
    // configVl 을 Number 로 변환해 슬라이더/zod(number) 가 저장값을 그대로 표시하도록 한다.
    // 파싱 불가(NaN) 값은 폴백되도록 제외한다.
    select: (items: ConfigItem[]): ConfigMap =>
      items.reduce<ConfigMap>((acc, item) => {
        const num = Number(item.configVl);
        if (item.configKey != null && !Number.isNaN(num)) {
          acc[String(item.configKey)] = num;
        }
        return acc;
      }, {}),
  });
}
