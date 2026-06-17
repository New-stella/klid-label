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
 * 라벨 일괄 PUT mutation. 성공 시 다음 캐시를 무효화:
 *  - LABEL_KEYS: 저장한 프레임의 **내부(internal) 라벨 키만** 무효화. 이 PUT 은 내부 전용
 *      /frames/{id}/labels 경로(putLabels)라 포털 user-label 데이터에 영향을 주지 않는다.
 *      따라서 LABEL_KEYS.all 광역 무효화로 포털 캐시(`...byFrame, 'portal'`)까지 churn 하지
 *      않고, 저장한 프레임의 internal 키(`...byFrame(srcSn,0), 'internal'`)만 무효화한다.
 *      (srcSn 미상이면 안전하게 LABEL_KEYS.all 로 폴백 — 거의 발생하지 않는 경로)
 *  - VIDEO_KEYS: 영상 목록/상세 진행률 갱신
 *  - ASSIGNMENT_KEYS: 작업 배정 진행률 갱신
 *  - REVIEW_KEYS: 검수 진행률 갱신
 *  - VERSION_KEYS: 버전 이력 패널 캐시 무효화 (저장 자체는 버전을 만들지 않지만, 검수 승인으로
 *      쌓인 버전 목록이 화면 상태와 어긋나지 않도록 보수적으로 무효화한다. 버전 스냅샷은
 *      검수 승인 시점에 BE가 생성한다 — SFR-08).
 *
 * 라벨 저장 후 프레임을 왕복하거나 작업 목록으로 빠져나갈 때 저장 전 캐시가 그대로 노출되는
 * 회귀(증상: "프레임 넘어가면 초기화") 방지를 위해 invalidate 한다.
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
      // 저장한 프레임의 internal 라벨 키만 무효화 (포털 캐시 churn 방지).
      if (srcSn !== undefined) {
        qc.invalidateQueries({ queryKey: [...LABEL_KEYS.byFrame(srcSn, 0), 'internal'] });
      } else {
        qc.invalidateQueries({ queryKey: LABEL_KEYS.all });
      }
      qc.invalidateQueries({ queryKey: VIDEO_KEYS.all });
      qc.invalidateQueries({ queryKey: ASSIGNMENT_KEYS.all });
      qc.invalidateQueries({ queryKey: REVIEW_KEYS.all });
      qc.invalidateQueries({ queryKey: VERSION_KEYS.all });
      options.onSuccess?.();
    },
    onError: options.onError,
  });
}
