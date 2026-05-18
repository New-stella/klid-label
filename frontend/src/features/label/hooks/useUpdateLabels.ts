import { useMutation, useQueryClient } from '@tanstack/react-query';

import { ASSIGNMENT_KEYS, LABEL_KEYS, REVIEW_KEYS, VIDEO_KEYS } from '@/lib/queryKeys';

import { putLabels } from '../api';
import type { Label } from '../types';

export interface UseUpdateLabelsOptions {
  onSuccess?: () => void;
  onError?: (err: unknown) => void;
}

/**
 * 라벨 일괄 PUT mutation. 성공 시 다음 캐시를 일괄 무효화:
 *  - LABEL_KEYS: 라벨 조회 (현재/형제 프레임) 재조회
 *  - VIDEO_KEYS: 영상 목록/상세 진행률 갱신
 *  - ASSIGNMENT_KEYS: 작업 배정 진행률 갱신
 *  - REVIEW_KEYS: 검수 진행률 갱신
 *
 * 라벨 저장 후 프레임을 왕복하거나 작업 목록으로 빠져나갈 때 저장 전 캐시가 그대로 노출되는
 * 회귀(증상: "프레임 넘어가면 초기화") 방지를 위해 일괄 invalidate 한다.
 */
export function useUpdateLabels(srcSn: number | undefined, options: UseUpdateLabelsOptions = {}) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (labels: Label[]) => {
      if (srcSn === undefined) {
        return Promise.reject(new Error('srcSn is required'));
      }
      return putLabels(srcSn, labels);
    },
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: LABEL_KEYS.all });
      qc.invalidateQueries({ queryKey: VIDEO_KEYS.all });
      qc.invalidateQueries({ queryKey: ASSIGNMENT_KEYS.all });
      qc.invalidateQueries({ queryKey: REVIEW_KEYS.all });
      options.onSuccess?.();
    },
    onError: options.onError,
  });
}
