import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import { ApiError } from '@/lib/api/errors';
import { REVIEW_KEYS } from '@/lib/queryKeys';

import {
  addIssueComment,
  createInquiry,
  listIssueThreads,
  resolveIssue,
} from '../api';
import type {
  AddIssueCommentRequest,
  CreateInquiryRequest,
  IssueComment,
  IssueThread,
} from '../types';

interface MutationOptions<T> {
  onSuccess?: (data: T) => void;
  onError?: (err: unknown) => void;
}

/** 409(낙관적 충돌) 여부 — 다른 사용자가 먼저 처리한 케이스. */
function isConflict(err: unknown): boolean {
  return err instanceof ApiError && err.status === 409;
}

/**
 * 영상 단위 이슈 스레드 목록 — BE GET /videos/{rawSn}/issues.
 * 반려(REJECTION) 이력 + 문의(INQUIRY) 통합. comments 는 REG_DT asc.
 */
export function useIssueThreads(rawSn: number | undefined) {
  return useQuery({
    queryKey: REVIEW_KEYS.issueThreads(rawSn ?? -1),
    queryFn: () => listIssueThreads(rawSn as number),
    enabled: typeof rawSn === 'number' && rawSn > 0,
    staleTime: 10_000,
  });
}

/**
 * 문의(INQUIRY) 등록 — WORKER/REVIEWER.
 * 성공 시 해당 영상의 issueThreads 무효화 → 스레드 즉시 갱신.
 */
export function useCreateInquiry(
  rawSn: number,
  options: MutationOptions<IssueThread> = {},
) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: CreateInquiryRequest) => createInquiry(rawSn, body),
    onSuccess: (data) => {
      qc.invalidateQueries({ queryKey: REVIEW_KEYS.issueThreads(rawSn) });
      options.onSuccess?.(data);
    },
    onError: options.onError,
  });
}

/**
 * 이슈 스레드 댓글 추가 — REVIEWER/WORKER.
 * REVIEWER 댓글 후 서버가 상태를 ANSWERED 로 전이하므로, 재조회로 신뢰(클라 전이 금지).
 * 409(낙관적 충돌) 시에도 서버 최신 상태로 동기화하기 위해 issueThreads 무효화를
 * 훅 내부에서 수행한다 (컴포넌트는 쿼리 키를 알 필요 없음 — 충돌 메시지만 관리).
 */
export function useAddIssueComment(
  rawSn: number,
  options: MutationOptions<IssueComment> = {},
) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (vars: { issueSn: number; body: AddIssueCommentRequest }) =>
      addIssueComment(vars.issueSn, vars.body),
    onSuccess: (data) => {
      qc.invalidateQueries({ queryKey: REVIEW_KEYS.issueThreads(rawSn) });
      options.onSuccess?.(data);
    },
    onError: (err) => {
      if (isConflict(err)) {
        qc.invalidateQueries({ queryKey: REVIEW_KEYS.issueThreads(rawSn) });
      }
      options.onError?.(err);
    },
  });
}

/**
 * 문의 해소 — REVIEWER 전용.
 * 성공/충돌(409) 모두 issueThreads 무효화로 서버 최신 상태 반영.
 * 충돌 invalidate 도 훅 내부에서 수행 (컴포넌트는 쿼리 키 의존 없음).
 */
export function useResolveIssue(
  rawSn: number,
  options: MutationOptions<void> = {},
) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (issueSn: number) => resolveIssue(issueSn),
    onSuccess: (data) => {
      qc.invalidateQueries({ queryKey: REVIEW_KEYS.issueThreads(rawSn) });
      options.onSuccess?.(data);
    },
    onError: (err) => {
      if (isConflict(err)) {
        qc.invalidateQueries({ queryKey: REVIEW_KEYS.issueThreads(rawSn) });
      }
      options.onError?.(err);
    },
  });
}
