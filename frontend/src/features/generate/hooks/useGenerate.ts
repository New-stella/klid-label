import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import {
  getBackgroundGenerateJob,
  requestBackgroundGenerate,
} from '../api';
import type { RequestBackgroundGenerateRequest } from '../types';

const GENERATE_KEYS = {
  all: ['generate'] as const,
  job: (id: number) => [...GENERATE_KEYS.all, 'job', id] as const,
};

interface MutationOptions<T> {
  onSuccess?: (data: T) => void;
  onError?: (err: unknown) => void;
}

/**
 * 배경영상 요청 mutation — POST 성공 시 navigate `/generate/result/:jobId`.
 */
export function useRequestBackgroundGenerate(
  options: MutationOptions<{ jobId: number }> = {},
) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: RequestBackgroundGenerateRequest) =>
      requestBackgroundGenerate(body),
    onSuccess: (data) => {
      qc.invalidateQueries({ queryKey: GENERATE_KEYS.all });
      options.onSuccess?.(data);
    },
    onError: options.onError,
  });
}

/**
 * 배경영상 요청 결과 조회 (단일 카드).
 */
export function useBackgroundGenerateJob(jobId: number | undefined) {
  return useQuery({
    queryKey:
      jobId !== undefined ? GENERATE_KEYS.job(jobId) : GENERATE_KEYS.all,
    queryFn: () => getBackgroundGenerateJob(jobId as number),
    enabled: jobId !== undefined,
  });
}
