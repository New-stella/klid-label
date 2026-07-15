import { useMutation, useQueryClient } from '@tanstack/react-query';

import { LABEL_KEYS } from '@/lib/queryKeys';

import { sam2TrackAllChunks, type Sam2TrackRequest, type Sam2TrackResponse } from '../api';

export interface UseSam2TrackOptions {
  onSuccess?: (data: Sam2TrackResponse) => void;
  onError?: (err: unknown) => void;
  /** 청크 순차 추적 진행률 — (누적 추적 프레임 수, 전체 대상 수). 청크 완료마다 호출. */
  onProgress?: (done: number, total: number) => void;
}

/**
 * SAM2 자동 추적 mutation hook.
 * - srcSn 미지정 시 즉시 reject.
 * - nextSrcSns 를 50개 이하 청크로 분할해 순차 호출(폴리곤 전파 체인) — BE `@Size(max=50)` 정합.
 * - 성공/부분실패 모두 LABEL_KEYS 무효화 (이미 서버에 반영된 청크 결과를 다음 프레임 라벨에 반영).
 */
export function useSam2Track(srcSn: number | undefined, options: UseSam2TrackOptions = {}) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (payload: Sam2TrackRequest) => {
      if (srcSn === undefined) {
        return Promise.reject(new Error('srcSn is required'));
      }
      return sam2TrackAllChunks(srcSn, payload, options.onProgress);
    },
    onSuccess: (data) => {
      qc.invalidateQueries({ queryKey: LABEL_KEYS.all });
      options.onSuccess?.(data);
    },
    onError: (err) => {
      // 부분 실패라도 이미 서버에 반영된 청크가 있을 수 있어 라벨을 재조회한다(부분 성공 유지).
      qc.invalidateQueries({ queryKey: LABEL_KEYS.all });
      options.onError?.(err);
    },
  });
}
