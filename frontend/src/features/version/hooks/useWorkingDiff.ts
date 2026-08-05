import { useQuery } from '@tanstack/react-query';

import { VERSION_KEYS } from '@/lib/queryKeys';

import { getWorkingDiff } from '../api';

/**
 * 버전 스냅샷 ↔ 현재 작업본(LS_DATA_LBL) 라벨 diff 조회.
 *
 * 승인 버전이 1건뿐인 프레임은 두 버전 비교 대상이 없어 변경 내역을 볼 수 없었다.
 * 이 훅은 버전 1건만으로 "그 승인 이후 지금까지"의 변경을 조회한다.
 *
 * 캐시 키는 `VERSION_KEYS.all` 하위라 라벨 저장·롤백의 broad invalidate 로 자동 갱신된다.
 *
 * [req: R1]
 *
 * @param srcSn query key 격리용 프레임 PK (실제 BE 는 versionHash 만 받는다)
 * @param hash  기준(from) 버전 해시. 미지정이면 쿼리 비활성.
 */
export function useWorkingDiff(srcSn: number | undefined, hash: string | undefined) {
  return useQuery({
    queryKey:
      srcSn !== undefined && hash
        ? VERSION_KEYS.workingDiff(srcSn, hash)
        : VERSION_KEYS.all,
    queryFn: () => getWorkingDiff(hash as string),
    enabled: srcSn !== undefined && Boolean(hash),
  });
}
