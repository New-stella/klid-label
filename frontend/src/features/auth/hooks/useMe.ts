import { useQuery } from '@tanstack/react-query';

import { USER_KEYS } from '@/lib/queryKeys';

import { getMe, type MeResponse } from '../api';

/**
 * 현재 세션 사용자 정보 조회.
 * - 로그인 후 한 번만 호출되도록 staleTime 무한대로 설정 (수동 invalidate 권장)
 */
export function useMe() {
  return useQuery<MeResponse>({
    queryKey: USER_KEYS.me(),
    queryFn: getMe,
    staleTime: Infinity,
    retry: false,
  });
}
