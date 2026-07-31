import { useMutation, useQueryClient } from '@tanstack/react-query';

import { AUGMENT_KEYS } from '@/lib/queryKeys';

import { acceptAugment, cancelAugment, rejectAugment, requestAugment } from '../api';
import type {
  AugmentCancelResult,
  RequestAugmentRequest,
  RequestAugmentResponse,
} from '../types';

interface MutationOptions<T> {
  onSuccess?: (data: T) => void;
  onError?: (err: unknown) => void;
}

/**
 * 증강 요청 옵션 — 성공 콜백에 **요청 본문(variables)** 을 함께 넘긴다.
 *
 * 응답의 `jobId` 는 결과 화면 경로로 쓸 수 없다(BE `AugmentRequestService#jobIdSeq` 는 어떤
 * 엔티티의 식별자도 아닌 placeholder 카운터다). 결과 API 의 경로변수는 **원본 영상 RAW_SN** 이므로
 * 호출부가 "무엇을 요청했는가"(= `videoIds`)를 근거로 이동해야 한다. 렌더 시점 상태 클로저 대신
 * 요청 본문을 그대로 받으면 요청 시점과 성공 시점 사이에 선택이 바뀌어도 어긋나지 않는다.
 */
interface RequestAugmentOptions {
  onSuccess?: (
    data: RequestAugmentResponse,
    variables: RequestAugmentRequest,
  ) => void;
  onError?: (err: unknown) => void;
}

/**
 * 증강 요청 생성 (POST /augments/request).
 */
export function useRequestAugment(options: RequestAugmentOptions = {}) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: RequestAugmentRequest) => requestAugment(body),
    onSuccess: (data, variables) => {
      qc.invalidateQueries({ queryKey: AUGMENT_KEYS.all });
      options.onSuccess?.(data, variables);
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

/**
 * 증강 요청 취소 (REVIEWER). 사유는 선택.
 *
 * 성공 시 증강 도메인 전체를 무효화해 결과(항목 상태)·진행상태가 함께 갱신된다.
 * `onSettled` 는 호출부의 연타 락 해제 지점이다(성공/실패 무관).
 */
export function useCancelAugment(
  options: MutationOptions<AugmentCancelResult> & { onSettled?: () => void } = {},
) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (vars: { id: number; reason?: string }) =>
      cancelAugment(vars.id, vars.reason),
    onSuccess: (data) => {
      qc.invalidateQueries({ queryKey: AUGMENT_KEYS.all });
      options.onSuccess?.(data);
    },
    onError: options.onError,
    onSettled: options.onSettled,
  });
}
