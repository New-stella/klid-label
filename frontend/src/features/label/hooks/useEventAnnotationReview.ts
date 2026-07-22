// Phase 4 — event_annotation 검토(승인/반려) 훅 (TanStack Query mutation, REVIEWER 전용).
//
// 승인/반려 성공 시 해당 영상(rawSn) 쿼리 무효화 → reviewStatus 재로드(서버 SoT 재동기화).

import { useMutation, useQueryClient } from '@tanstack/react-query';

import { EVENT_ANNOTATION_KEYS } from '@/lib/queryKeys';

import {
  approveEventAnnotation,
  rejectEventAnnotation,
} from '../api/eventAnnotation';

export interface UseEventAnnotationReviewOptions {
  onApproveSuccess?: () => void;
  onApproveError?: (err: unknown) => void;
  onRejectSuccess?: () => void;
  onRejectError?: (err: unknown) => void;
}

/**
 * event_annotation 검토 승인/반려 mutation 묶음. 성공 시 영상 단위 쿼리 무효화.
 * rawSn 미정이면 mutation 은 거절된다(경로 조작 방지).
 */
export function useEventAnnotationReview(
  rawSn: number | undefined,
  options: UseEventAnnotationReviewOptions = {},
) {
  const qc = useQueryClient();

  const invalidate = () => {
    if (rawSn !== undefined) {
      qc.invalidateQueries({ queryKey: EVENT_ANNOTATION_KEYS.byVideo(rawSn) });
    }
  };

  const approve = useMutation<void, unknown, void>({
    mutationFn: () => {
      if (rawSn === undefined) return Promise.reject(new Error('rawSn is required'));
      return approveEventAnnotation(rawSn);
    },
    onSuccess: () => {
      invalidate();
      options.onApproveSuccess?.();
    },
    onError: options.onApproveError,
  });

  const reject = useMutation<void, unknown, string>({
    mutationFn: (reason: string) => {
      if (rawSn === undefined) return Promise.reject(new Error('rawSn is required'));
      return rejectEventAnnotation(rawSn, reason);
    },
    onSuccess: () => {
      invalidate();
      options.onRejectSuccess?.();
    },
    onError: options.onRejectError,
  });

  return { approve, reject };
}
