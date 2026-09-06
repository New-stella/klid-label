import { useQuery } from '@tanstack/react-query';

import { PORTAL_KEYS } from '@/lib/queryKeys';

import { listUserWorks } from '../api';

/**
 * 포털 진입 화면의 「내 작업」 목록 훅. @design API-225, SCREEN-028
 *
 * BE: GET /v1/portal/user-works — 본인 업로드 자산 전부 + 본인 저작물이 있는 데이터마트 영상.
 *
 * ★ **데이터마트 카탈로그를 조회하지 않는다** — 데이터마트 영상 전체를 훑어보는 목록은 포털(Host)이
 *   자기 화면에서 제공한다. 구 `useDatamartVideos` 는 그 카탈로그를 그리고 있었고, 그래서 작업하지
 *   않은 영상까지 목록에 뜨고 진입이 늘 첫 프레임이라 이어쓰기가 되지 않았다.
 *
 * ⚠ 만료 예정일(`expiresOn`)은 **조회 시점에 계산되는 파생값**이라 화면이 따로 보관하지 않는다.
 *   훅이 `staleTime` 을 늘려 잡지 않는 이유가 이것이다(공용 기본값을 따른다).
 */
export function useUserWorks(params: { page?: number; size?: number } = {}) {
  return useQuery({
    queryKey: PORTAL_KEYS.userWorks(params as Record<string, unknown>),
    queryFn: () => listUserWorks(params),
  });
}
