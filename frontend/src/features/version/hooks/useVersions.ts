import { useQuery } from '@tanstack/react-query';

import { VERSION_KEYS } from '@/lib/queryKeys';

import { listVersions } from '../api';

/**
 * 프레임(srcSn)의 버전(커밋) 이력 조회.
 *
 * @param srcSn LS_DATA_SRC.SRC_SN — 프레임 단위 PK.
 *              VERSION_KEYS.history(srcSn) 캐시 키로 격리되어 라벨 저장·롤백 시 invalidate 된다.
 */
export function useVersions(srcSn: number | undefined) {
  return useQuery({
    queryKey: srcSn !== undefined ? VERSION_KEYS.history(srcSn) : VERSION_KEYS.all,
    queryFn: () => listVersions(srcSn as number),
    enabled: srcSn !== undefined,
  });
}
