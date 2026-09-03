/**
 * 포털 업로드 자산 상세 훅(API-140).
 *
 * 화면이 `useQuery` 를 직접 부르지 않게 감싼다. 마킹 화면은 이 응답으로 <b>진입 차단 세 축</b>
 * (소유 · 업로드 완료 · 자산 종류)과 저장 가능 여부, 그리고 지점 산출에 쓸 길이·초당 프레임 수를
 * 얻는다. [@design SCREEN-045]
 *
 * ⚠ 재시도하지 않는다 — 남의 자산·없는 자산은 거절이 확정이고, 되풀이해 봐야 같은 답이 온다.
 *   차단 안내가 늦게 뜨는 것보다 곧바로 뜨는 편이 낫다.
 */
import { useQuery } from '@tanstack/react-query';

import { PORTAL_KEYS } from '@/lib/queryKeys';

import { getUpload } from '../api';

export function useUploadDetail(uldSn: number | undefined) {
  return useQuery({
    queryKey: PORTAL_KEYS.uploadDetail(uldSn ?? -1),
    queryFn: () => getUpload(uldSn as number),
    enabled: uldSn !== undefined && uldSn > 0,
    retry: false,
  });
}
