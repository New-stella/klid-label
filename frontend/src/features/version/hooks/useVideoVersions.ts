import { useQuery } from '@tanstack/react-query';

import { VERSION_KEYS } from '@/lib/queryKeys';

import { listVideoVersions } from '../api';

/**
 * 영상(rawSn)의 산출 버전 목록 — 「시작 버전 선택」 선택지.
 *
 * 프레임 단위 이력(`useVersions`)과 <b>다른 축</b>이다: 이쪽은 영상 단위이며 번호는 관제가 픽업하는
 * 산출 폴더 `v{n}` 과 같다. 캐시 키도 분리돼 있어 한쪽 결과가 다른 쪽 자리에 뜨지 않는다.
 *
 * @design D4
 * @req R6
 */
export function useVideoVersions(rawSn: number | undefined) {
  return useQuery({
    queryKey: rawSn !== undefined ? VERSION_KEYS.videoVersions(rawSn) : VERSION_KEYS.all,
    queryFn: () => listVideoVersions(rawSn as number),
    enabled: rawSn !== undefined,
  });
}
