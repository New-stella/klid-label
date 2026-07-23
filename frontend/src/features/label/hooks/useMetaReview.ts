// R6(Phase 6-D) — 시계열 메타 검토(승인/반려) 훅 (TanStack Query mutation, REVIEWER 전용).
//
// event_annotation 의 useEventAnnotationReview 를 미러링한다. 승인/반려 성공 시
// 해당 프레임(srcSn) 메타 쿼리를 무효화 → reviewStatus 재로드(서버 SoT 재동기화).
// 메타는 프레임 단위로 다수 검토행을 가질 수 있으므로 mutation 은 metaReviewSn 을 인자로 받는다.

import { useMutation, useQueryClient } from '@tanstack/react-query';

import { approveMetaReview, rejectMetaReview } from '@/features/auto/api';
import { META_KEYS } from '@/lib/queryKeys';

export interface UseMetaReviewOptions {
  onApproveSuccess?: () => void;
  onApproveError?: (err: unknown) => void;
  onRejectSuccess?: () => void;
  onRejectError?: (err: unknown) => void;
}

export interface MetaRejectVariables {
  metaReviewSn: number;
  reason: string;
}

/**
 * 시계열 메타 검토 승인/반려 mutation 묶음. 성공 시 메타 쿼리 무효화.
 * srcSn 미정이면 무효화만 생략(mutation 은 metaReviewSn 만 있으면 동작).
 */
export function useMetaReview(
  srcSn: number | undefined,
  options: UseMetaReviewOptions = {},
) {
  const qc = useQueryClient();

  const invalidate = () => {
    qc.invalidateQueries({ queryKey: META_KEYS.all });
    if (srcSn !== undefined) {
      qc.invalidateQueries({ queryKey: META_KEYS.byVideo(srcSn) });
    }
  };

  const approve = useMutation<void, unknown, number>({
    mutationFn: (metaReviewSn: number) => approveMetaReview(metaReviewSn),
    onSuccess: () => {
      invalidate();
      options.onApproveSuccess?.();
    },
    onError: options.onApproveError,
  });

  const reject = useMutation<void, unknown, MetaRejectVariables>({
    mutationFn: ({ metaReviewSn, reason }: MetaRejectVariables) =>
      rejectMetaReview(metaReviewSn, reason),
    onSuccess: () => {
      invalidate();
      options.onRejectSuccess?.();
    },
    onError: options.onRejectError,
  });

  return { approve, reject };
}
