import { useQuery } from '@tanstack/react-query';

import { SYSCONFIG_KEYS } from '@/lib/queryKeys';

import { getConfigs } from '../api';
import type { ConfigItem, ConfigMap } from '../types';

interface UseConfigsOptions {
  /**
   * react-query 의 enabled 와 동일. 기본값 true.
   *
   * ★ `GET /v1/manage/configs` 는 REVIEWER 전용이다(SecurityConfig 의 `/v1/manage/**` 매처 +
   * 컨트롤러 `@PreAuthorize`). WORKER 화면에서 조건 없이 호출하면 진입할 때마다 403 이 쌓인다
   * (실측: 라벨링 진입 1회당 403 2건). 실패를 조용히 삼키는 대신 **호출 자체를 막는다** —
   * 삼키면 콘솔만 조용해지고 불필요한 왕복은 그대로 남는다.
   */
  enabled?: boolean;
}

export function useConfigs(options: UseConfigsOptions = {}) {
  const { enabled = true } = options;
  return useQuery({
    queryKey: SYSCONFIG_KEYS.presets(),
    queryFn: getConfigs,
    enabled,
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
