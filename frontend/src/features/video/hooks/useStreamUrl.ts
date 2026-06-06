import { useQuery } from '@tanstack/react-query';

import { VIDEO_KEYS } from '@/lib/queryKeys';

import { getStreamUrl } from '../api';

/**
 * 영상 스트림 단기 서명 URL 훅.
 *
 * <p><video> 가 Authorization 헤더를 못 붙이는 문제를 우회한다. 짧은 TTL HMAC 서명 URL 을 발급받아
 * <video src> 로 사용한다. 만료(401) 시 화면에서 refetch 로 재발급한다.
 *
 * staleTime 을 0 으로 두어 마운트/refetch 마다 새 서명을 받는다.
 */
// 서버 stream sign TTL(60s)의 80% — 만료 직전까지 캐시 재사용, 그 후 신선한 서명 재발급.
const SIGN_STALE_MS = 48_000;

export function useStreamUrl(rawSn: number | undefined) {
  return useQuery({
    queryKey: VIDEO_KEYS.streamUrl(rawSn ?? -1),
    queryFn: () => getStreamUrl(rawSn as number),
    enabled: rawSn !== undefined && rawSn > 0,
    staleTime: SIGN_STALE_MS,
    gcTime: 0,
    retry: false,
  });
}
