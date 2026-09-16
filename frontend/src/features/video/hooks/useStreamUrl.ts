import { useQuery } from '@tanstack/react-query';

import { VIDEO_KEYS } from '@/lib/queryKeys';

import { getStreamUrl } from '../api';

/**
 * 영상 재생 주소 발급 훅. [@design API-114] [@design SCREEN-006]
 *
 * <p><video> 가 Authorization 헤더를 못 붙이는 문제를 우회한다. 재생 주소를 발급받아 <video src> 로
 * 쓰고, 재생 구간 인증은 발급 응답과 함께 내려온 쿠키로 판정된다.
 *
 * ★ <b>스스로 다시 받지 않는다</b>(`staleTime: Infinity` · 창 복귀·재연결 시 재조회 안 함).
 *   재발급은 <b>재생이 실제로 실패했을 때</b> 화면이 `refetch` 로 부를 때만 일어난다.
 *   응답의 만료 시각·유효 초는 인증 수명이 아니라서 재발급 시점 판단에 쓰지 않는다(API-114).
 *   ⚠ 구 동작(`staleTime` 48초 = 서명 수명 60초의 80%)은 폐기 — 창에 다시 돌아오면 조용히 새 주소를
 *   받아 `src` 가 바뀌었고, 그때마다 재생 위치가 처음으로 돌아갈 수 있었다.
 * ★ `gcTime: 0` 이라 화면을 떠나면 캐시가 남지 않는다 — 다음 진입에서 새로 받는다.
 */
export function useStreamUrl(rawSn: number | undefined) {
  return useQuery({
    queryKey: VIDEO_KEYS.streamUrl(rawSn ?? -1),
    queryFn: () => getStreamUrl(rawSn as number),
    enabled: rawSn !== undefined && rawSn > 0,
    staleTime: Infinity,
    gcTime: 0,
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
    retry: false,
  });
}
