import { useMutation, useQueryClient } from '@tanstack/react-query';

import { LABEL_KEYS } from '@/lib/queryKeys';

import { requestSam2Track, type Sam2TrackRequest, type Sam2TrackResponse } from '../api';

export interface UseSam2TrackOptions {
  onSuccess?: (data: Sam2TrackResponse) => void;
  onError?: (err: unknown) => void;
}

/**
 * SAM2 자동 추적 mutation hook.
 * - srcSn 미지정 시 즉시 reject.
 * - 성공 시 LABEL_KEYS 무효화 (다음 프레임 라벨 재조회).
 */
export function useSam2Track(srcSn: number | undefined, options: UseSam2TrackOptions = {}) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (payload: Sam2TrackRequest) => {
      if (srcSn === undefined) {
        return Promise.reject(new Error('srcSn is required'));
      }
      return requestSam2Track(srcSn, payload);
    },
    onSuccess: (data) => {
      qc.invalidateQueries({ queryKey: LABEL_KEYS.all });
      options.onSuccess?.(data);
    },
    onError: options.onError,
  });
}
