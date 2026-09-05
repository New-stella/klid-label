// 포털 프레임 메타 Load·저장 훅 (API-234·API-235).
//
// ★컴포넌트가 useQuery 를 직접 부르지 않는다 — 도메인 훅으로 감싸 캐시 키·무효화 범위를 한곳에 둔다.

// @design API-234 @design API-235

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import { PORTAL_KEYS } from '@/lib/queryKeys';

import { getPortalFrameMeta, savePortalFrameMeta } from '../api';
import type { PortalFrameMeta, PortalMetaSaveItem } from '../types';

/** 프레임 메타 조회. `srcSn` 이 없으면 비활성. */
export function usePortalFrameMeta(srcSn: number | undefined) {
  return useQuery({
    queryKey: PORTAL_KEYS.frameMeta(srcSn ?? -1),
    queryFn: () => getPortalFrameMeta(srcSn as number),
    enabled: srcSn !== undefined && Number.isFinite(srcSn) && srcSn > 0,
    // 실패는 대개 인가·게이트(403·404·412)라 재시도해도 결과가 같다. 되풀이해 두들기지 않는다.
    retry: false,
  });
}

export interface UsePortalSaveFrameMetaOptions {
  onSuccess?: (data: PortalFrameMeta) => void;
  onError?: (error: unknown) => void;
}

/**
 * 프레임 메타 저장.
 *
 * 저장 응답이 곧 «저장 후 다시 병합해 읽은 결과»라 그것을 캐시에 그대로 심는다 — 재조회를 한 번
 * 더 하면 그 사이에 화면이 옛 값을 그린다.
 *
 * ★무효화(invalidateQueries)는 <b>걸지 않는다</b> — 걸 곳이 없기 때문이다. 이 메타가 앉는 캐시
 * 자리는 {@code PORTAL_KEYS.frameMeta(srcSn)} <b>하나뿐</b>이라 여기 심은 값이 그 키를 보는 모든
 * 화면에 그대로 닿는다. 무효화를 더하면 같은 값을 받아 오는 왕복이 한 번 느는 것이 전부다.
 * 이 메타를 옮겨 담는 <b>두 번째 캐시 자리</b>가 생기면 그때 다시 판단할 것.
 */
export function usePortalSaveFrameMeta(
  srcSn: number | undefined,
  options: UsePortalSaveFrameMetaOptions = {},
) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (items: PortalMetaSaveItem[]) => savePortalFrameMeta(srcSn as number, items),
    onSuccess: (data) => {
      if (srcSn !== undefined) {
        queryClient.setQueryData(PORTAL_KEYS.frameMeta(srcSn), data);
      }
      options.onSuccess?.(data);
    },
    onError: (error) => options.onError?.(error),
  });
}
