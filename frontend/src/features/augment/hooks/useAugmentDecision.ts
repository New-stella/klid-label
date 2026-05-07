import { useMutation, useQueryClient } from '@tanstack/react-query';

import { AUGMENT_KEYS } from '@/lib/queryKeys';

import { acceptAugment, rejectAugment, requestAugment } from '../api';
import type { RequestAugmentRequest } from '../types';

interface MutationOptions<T> {
  onSuccess?: (data: T) => void;
  onError?: (err: unknown) => void;
}

/**
 * 증강 요청 생성 (POST /augments).
 */
export function useRequestAugment(
  options: MutationOptions<{ jobId: number }> = {},
) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: RequestAugmentRequest) => requestAugment(body),
    onSuccess: (data) => {
      qc.invalidateQueries({ queryKey: AUGMENT_KEYS.all });
      options.onSuccess?.(data);
    },
    onError: options.onError,
  });
}

/**
 * 증강 결과 채택 (PENDING → ACCEPTED).
 */
export function useAcceptAugment(options: MutationOptions<unknown> = {}) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => acceptAugment(id),
    onSuccess: (data) => {
      qc.invalidateQueries({ queryKey: AUGMENT_KEYS.all });
      options.onSuccess?.(data);
    },
    onError: options.onError,
  });
}

/**
 * 증강 결과 거부 (PENDING → REJECTED). 거부 사유 zod 검증 후 호출.
 */
export function useRejectAugment(options: MutationOptions<unknown> = {}) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (vars: { id: number; reason: string }) =>
      rejectAugment(vars.id, vars.reason),
    onSuccess: (data) => {
      qc.invalidateQueries({ queryKey: AUGMENT_KEYS.all });
      options.onSuccess?.(data);
    },
    onError: options.onError,
  });
}
