// Phase 4 — event_annotation 저장 훅 (TanStack Query mutation).
//
// 성공 시 해당 영상(rawSn) 쿼리 무효화 → 재로드(서버 SoT 재동기화).

import { useMutation, useQueryClient } from '@tanstack/react-query';

import { EVENT_ANNOTATION_KEYS } from '@/lib/queryKeys';

import {
  putEventAnnotation,
  type EventAnnotationInfo,
  type EventAnnotationPayload,
} from '../api/eventAnnotation';

export interface UseUpdateEventAnnotationOptions {
  onSuccess?: () => void;
  onError?: (err: unknown) => void;
}

/** event_annotation 저장/수정 mutation. 성공 시 영상 단위 쿼리 무효화. */
export function useUpdateEventAnnotation(
  rawSn: number | undefined,
  options: UseUpdateEventAnnotationOptions = {},
) {
  const qc = useQueryClient();
  return useMutation<EventAnnotationInfo, unknown, EventAnnotationPayload>({
    mutationFn: (payload: EventAnnotationPayload) => {
      if (rawSn === undefined) {
        return Promise.reject(new Error('rawSn is required'));
      }
      return putEventAnnotation(rawSn, payload);
    },
    onSuccess: () => {
      if (rawSn !== undefined) {
        qc.invalidateQueries({ queryKey: EVENT_ANNOTATION_KEYS.byVideo(rawSn) });
      }
      options.onSuccess?.();
    },
    onError: options.onError,
  });
}
