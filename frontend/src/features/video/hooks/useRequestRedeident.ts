import { useMutation, useQueryClient } from '@tanstack/react-query';

import { VIDEO_KEYS } from '@/lib/queryKeys';

import { requestRedeident } from '../api';
import type { RedeidentResult } from '../types';

interface MutationOptions {
  onSuccess?: (data: RedeidentResult) => void;
  onError?: (err: unknown) => void;
}

/**
 * 영상 재비식별 요청 (SC-009, POST /videos/{rawSn}/redeident).
 *
 * 성공 시 해당 영상 상세 쿼리를 invalidate 하여 deIdntfYn/처리 단계가 갱신되도록 한다.
 */
export function useRequestRedeident(rawSn: number, options: MutationOptions = {}) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => requestRedeident(rawSn),
    onSuccess: (data) => {
      qc.invalidateQueries({ queryKey: VIDEO_KEYS.detail(rawSn) });
      options.onSuccess?.(data);
    },
    onError: options.onError,
  });
}
