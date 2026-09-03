/**
 * 포털 증강 요청 단건 조회 훅(API-233) — 결과 확인 구역이 쓴다.
 *
 * ⚠ 재시도하지 않는다 — 남의 요청·없는 요청은 서버가 한 코드로 묶어 확정 거부하므로 되풀이해야
 *   같은 답이 온다. 안내가 늦게 뜨는 것보다 곧바로 뜨는 편이 낫다(업로드 상세 훅과 같은 관례).
 *
 * ⚠ 여기서는 폴링하지 않는다 — 목록이 이미 도착 여부를 물어보고 있고, 같은 사실을 두 곳에서
 *   물으면 요청만 두 배가 된다. 목록 상태가 바뀌면 사용자가 그 행을 다시 고른다.
 *
 * @design SCREEN-044
 * @design API-233
 */
import { useQuery } from '@tanstack/react-query';

import { PORTAL_KEYS } from '@/lib/queryKeys';

import { getPortalAugment } from '../api';

export function usePortalAugment(augSn: number | undefined) {
  return useQuery({
    queryKey: PORTAL_KEYS.augmentDetail(augSn ?? -1),
    queryFn: () => getPortalAugment(augSn as number),
    enabled: augSn !== undefined && augSn > 0,
    retry: false,
  });
}
