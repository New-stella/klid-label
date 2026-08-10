import { useQuery } from '@tanstack/react-query';

import { SYSCONFIG_KEYS } from '@/lib/queryKeys';

import { getConfigs } from '../api';
import type { ConfigItem, ConfigStringMap } from '../types';

/**
 * 설정값을 **문자열 원문 그대로** 읽는다 (R11).
 *
 * `useConfigs` 는 값을 `Number()` 로 변환하고 `NaN` 이면 **버린다**(숫자 설정만 다루던 시절의 규칙).
 * 연동 서버 주소는 문자열이라 그 경로로는 화면에 도달하지 못한다.
 *
 * ⚠ **queryKey 를 `useConfigs` 와 같게 둔다** — 같은 `GET /v1/manage/configs` 응답을 서로 다른
 * `select` 로 볼 뿐이므로, 키를 나누면 같은 응답을 두 번 받아온다.
 *
 * ⚠ 응답에는 **DB 에 행이 있는 키만** 담긴다. 연동 주소는 시드하지 않는 것이 설계이므로, 한 번도
 * 저장한 적 없는 주소는 여기에 **없는 것이 정상**이며 그때 실제로 쓰이는 값은 배포 기본값이다.
 */
export function useConfigStrings(options: { enabled?: boolean } = {}) {
  const { enabled = true } = options;
  return useQuery({
    queryKey: SYSCONFIG_KEYS.presets(),
    queryFn: getConfigs,
    enabled,
    select: (items: ConfigItem[]): ConfigStringMap =>
      items.reduce<ConfigStringMap>((acc, item) => {
        if (item.configKey != null && item.configVl != null) {
          acc[String(item.configKey)] = String(item.configVl);
        }
        return acc;
      }, {}),
  });
}
