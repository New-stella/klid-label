import { useMutation, useQueryClient } from '@tanstack/react-query';

import { REVIEW_KEYS, VIDEO_KEYS, ASSIGNMENT_KEYS } from '@/lib/queryKeys';

import {
  addIssue,
  approveReview,
  cancelSubmitReview,
  rejectReview,
  startReview,
  submitReview,
} from '../api';
import type { AddIssueRequest, RejectRequest, ApproveRequest } from '../types';

interface MutationOptions<T> {
  onSuccess?: (data: T) => void;
  onError?: (err: unknown) => void;
}

/**
 * 검수 시작 (REVIEW_PENDING → REVIEWING).
 */
export function useStartReview(options: MutationOptions<unknown> = {}) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (reviewId: number) => startReview(reviewId),
    onSuccess: (data) => {
      qc.invalidateQueries({ queryKey: REVIEW_KEYS.all });
      qc.invalidateQueries({ queryKey: VIDEO_KEYS.all });
      options.onSuccess?.(data);
    },
    onError: options.onError,
  });
}

/**
 * 검수 승인 (REVIEWING → COMPLETED).
 */
export function useApproveReview(options: MutationOptions<unknown> = {}) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (vars: { reviewId: number; body?: ApproveRequest }) =>
      approveReview(vars.reviewId, vars.body),
    onSuccess: (data) => {
      qc.invalidateQueries({ queryKey: REVIEW_KEYS.all });
      qc.invalidateQueries({ queryKey: VIDEO_KEYS.all });
      qc.invalidateQueries({ queryKey: ASSIGNMENT_KEYS.all });
      options.onSuccess?.(data);
    },
    onError: options.onError,
  });
}

/**
 * 검수 반려 (REVIEWING → REJECTED + 작업 IN_PROGRESS 복귀 — BE 처리).
 */
export function useRejectReview(options: MutationOptions<unknown> = {}) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (vars: { reviewId: number; body: RejectRequest }) =>
      rejectReview(vars.reviewId, vars.body),
    onSuccess: (data) => {
      qc.invalidateQueries({ queryKey: REVIEW_KEYS.all });
      qc.invalidateQueries({ queryKey: VIDEO_KEYS.all });
      qc.invalidateQueries({ queryKey: ASSIGNMENT_KEYS.all });
      options.onSuccess?.(data);
    },
    onError: options.onError,
  });
}

/**
 * 작업자 검수 제출 (IN_PROGRESS → REVIEW_PENDING).
 */
export function useSubmitReview(options: MutationOptions<unknown> = {}) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (videoId: number) => submitReview(videoId),
    onSuccess: (data) => {
      qc.invalidateQueries({ queryKey: VIDEO_KEYS.all });
      qc.invalidateQueries({ queryKey: ASSIGNMENT_KEYS.all });
      qc.invalidateQueries({ queryKey: REVIEW_KEYS.all });
      options.onSuccess?.(data);
    },
    onError: options.onError,
  });
}

/**
 * 작업자 검수 제출 취소 (REVIEW_PENDING → ASSIGNED).
 *
 * submit 과 동일한 캐시(REVIEW/VIDEO/ASSIGNMENT)를 invalidate 하여 제출 버튼 가드가 재평가된다.
 */
export function useCancelSubmitReview(options: MutationOptions<unknown> = {}) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (videoId: number) => cancelSubmitReview(videoId),
    onSuccess: (data) => {
      qc.invalidateQueries({ queryKey: VIDEO_KEYS.all });
      qc.invalidateQueries({ queryKey: ASSIGNMENT_KEYS.all });
      qc.invalidateQueries({ queryKey: REVIEW_KEYS.all });
      options.onSuccess?.(data);
    },
    onError: options.onError,
  });
}

/**
 * 이슈 추가 — 사이드바 카드 누적.
 */
export function useAddIssue(options: MutationOptions<unknown> = {}) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (vars: { reviewId: number; body: AddIssueRequest }) =>
      addIssue(vars.reviewId, vars.body),
    onSuccess: (data, vars) => {
      qc.invalidateQueries({ queryKey: REVIEW_KEYS.detail(vars.reviewId) });
      options.onSuccess?.(data);
    },
    onError: options.onError,
  });
}
