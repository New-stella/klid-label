// 포털 이벤트 어노테이션 Load·저장 훅 (API-236·API-237). 영상(rawSn) 단위다.

// @design API-236 @design API-237

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import { PORTAL_KEYS } from '@/lib/queryKeys';
import type { EventAnnotationPayload } from '@/features/label/api/eventAnnotation';

import { getPortalEventAnnotation, savePortalEventAnnotation } from '../api';
import type { PortalEventAnnotation } from '../types';

/**
 * 이벤트 어노테이션 조회.
 *
 * ⚠ 이 창구는 비식별 누락 신고 구간에서도 <b>막히지 않는다</b> — 근거는 <b>창구별 사양</b>이다.
 * 이 조회 창구는 그 거부 응답 자체를 갖지 않는다(API-236).
 *
 * ★「개인정보의 위치를 특정하는 산출물만 막는다」로 일반화하지 말 것 — <b>사실이 아니다</b>.
 *   형제 메타 조회 창구(API-234)는 위치를 특정하지 않는데도 같은 구간에서 거부된다. 그 잘못된
 *   근거로 「메타 조회는 안 막힌다」를 유도하면 틀린다.
 *
 * 저장 축은 이 창구도 같은 구간에서 거부되며, 두 축을 「일관성」을 이유로 통일하지 말 것.
 */
export function usePortalEventAnnotation(rawSn: number | undefined) {
  return useQuery({
    queryKey: PORTAL_KEYS.eventAnnotation(rawSn ?? -1),
    queryFn: () => getPortalEventAnnotation(rawSn as number),
    enabled: rawSn !== undefined && Number.isFinite(rawSn) && rawSn > 0,
    retry: false,
  });
}

export interface UsePortalSaveEventAnnotationOptions {
  onSuccess?: (data: PortalEventAnnotation) => void;
  onError?: (error: unknown) => void;
}

/** 이벤트 어노테이션 저장 — 영상당 한 벌이며 다시 저장하면 덮어쓴다. */
export function usePortalSaveEventAnnotation(
  rawSn: number | undefined,
  options: UsePortalSaveEventAnnotationOptions = {},
) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (annotation: EventAnnotationPayload) =>
      savePortalEventAnnotation(rawSn as number, annotation),
    onSuccess: (data) => {
      if (rawSn !== undefined) {
        queryClient.setQueryData(PORTAL_KEYS.eventAnnotation(rawSn), data);
      }
      options.onSuccess?.(data);
    },
    onError: (error) => options.onError?.(error),
  });
}
