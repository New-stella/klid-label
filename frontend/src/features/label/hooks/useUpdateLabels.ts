import { useMutation, useQueryClient } from '@tanstack/react-query';

import {
  ASSIGNMENT_KEYS,
  LABEL_KEYS,
  REVIEW_KEYS,
  VERSION_KEYS,
  VIDEO_KEYS,
} from '@/lib/queryKeys';

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
 *  - VERSION_KEYS: 버전 이력(작업이력 패널)에 신규 커밋 즉시 반영
 *    — BE LabelService.bulkUpsert() 가 versionService.commit() 으로 Gitea 커밋 +
 *      LS_LABEL_VERSION INSERT 까지 수행하므로 저장 직후 새 커밋이 발생한다.
 *      VERSION_KEYS invalidate 누락 시 작업이력 패널에 새 커밋이 새로고침 전까지 안 보이는
 *      증상이 발생한다 (회귀 가드: useUpdateLabels.invalidate.test.tsx).
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
      qc.invalidateQueries({ queryKey: VERSION_KEYS.all });
      options.onSuccess?.();
    },
    onError: options.onError,
  });
}
