import { zodResolver } from '@hookform/resolvers/zod';
import { useMemo, useState } from 'react';
import { useForm } from 'react-hook-form';
import { z } from 'zod';

import { ApiError } from '@/lib/api/errors';
import { cn } from '@/lib/cn';
import { useUiStore } from '@/stores/useUiStore';

import { formatDateTime } from '../formatDateTime';
import {
  useAddIssueComment,
  useCreateInquiry,
  useIssueThreads,
  useResolveIssue,
} from '../hooks/useIssueThreads';
import { ISSUE_STATUS_LABEL, ISSUE_TYPE_LABEL } from '../issueLabels';
import { ISSUE_STATUS, ISSUE_TYPE, type IssueThread } from '../types';

export type IssueThreadMode = 'worker' | 'reviewer';

export interface IssueThreadPanelProps {
  rawSn: number;
  /** 'worker' = 라벨링 화면(WORKER), 'reviewer' = 검수 화면(REVIEWER). */
  mode: IssueThreadMode;
  /** 다크 패널(라벨링 화면)에 마운트 시 true. */
  dark?: boolean;
}

// 본문 1~1000자 — RejectModal 선례와 동일 정책.
const contentSchema = z.object({
  content: z.string().min(1, '내용을 입력하세요').max(1000, '최대 1000자'),
});
type ContentForm = z.infer<typeof contentSchema>;

/**
 * INQUIRY 가 RESOLVED 면 댓글 입력 잠금. REJECTION 은 RESOLVED 여도 입력 가능 (BE 정책).
 */
function isCommentLocked(thread: IssueThread): boolean {
  return (
    thread.issueTypeCd === ISSUE_TYPE.INQUIRY &&
    thread.issueSttsCd === ISSUE_STATUS.RESOLVED
  );
}

/**
 * SCR-LABEL-001 / SCR-REVIEW-002 — 검수자↔작업자 이슈 스레드 패널.
 *
 * - 반려(REJECTION) 이력 + 문의(INQUIRY) 통합 스레드.
 * - WORKER: 문의 등록 + 스레드 확인. REVIEWER: 댓글·해소.
 * - 상태 전이는 서버 응답 신뢰 (클라 전이 금지) — mutation 후 issueThreads invalidate.
 *
 * 보안:
 * - 본문은 텍스트 노드로만 렌더 (dangerouslySetInnerHTML 절대 미사용 — XSS 방어).
 *   개행은 whitespace-pre-wrap.
 * - content 는 zod 1~1000자 검증 후 전송.
 */
export function IssueThreadPanel({ rawSn, mode, dark = false }: IssueThreadPanelProps) {
  const { data, isLoading, error } = useIssueThreads(rawSn);
  const threads = useMemo(() => data ?? [], [data]);

  const unresolvedInquiryCount = useMemo(
    () =>
      threads.filter(
        (t) =>
          t.issueTypeCd === ISSUE_TYPE.INQUIRY &&
          t.issueSttsCd !== ISSUE_STATUS.RESOLVED,
      ).length,
    [threads],
  );

  const textBase = dark ? 'text-gray-200' : 'text-primary';
  const subText = dark ? 'text-gray-400' : 'text-neutral';
  const cardBorder = dark ? 'border-gray-700 bg-gray-900' : 'border-border bg-white';

  return (
    <section
      className="flex flex-col gap-3 p-3"
      data-testid="issue-thread-panel"
      aria-label="이슈 스레드"
    >
      <header className="flex items-center justify-between">
        <h2 className={cn('text-section-title font-semibold', textBase)}>이슈 스레드</h2>
        <span
          data-testid="unresolved-inquiry-count"
          className="inline-flex min-w-5 items-center justify-center rounded-full bg-red-100 px-1.5 py-0.5 text-sub font-medium text-red-700"
          aria-label={`미해소 문의 ${unresolvedInquiryCount}건`}
        >
          {unresolvedInquiryCount}
        </span>
      </header>

      {mode === 'worker' && (
        <InquiryForm rawSn={rawSn} dark={dark} />
      )}

      {isLoading ? (
        <p className={cn('text-sub', subText)}>이슈 로딩 중...</p>
      ) : error ? (
        <p className="text-sub text-danger">이슈를 불러오지 못했습니다.</p>
      ) : threads.length === 0 ? (
        <p className={cn('text-sub', subText)} data-testid="issue-thread-empty">
          등록된 이슈가 없습니다
        </p>
      ) : (
        <ul className="flex flex-col gap-3" data-testid="issue-thread-list">
          {threads.map((thread) => (
            <li key={thread.issueSn}>
              <ThreadCard
                thread={thread}
                rawSn={rawSn}
                mode={mode}
                cardBorder={cardBorder}
                textBase={textBase}
                subText={subText}
                dark={dark}
              />
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

interface InquiryFormProps {
  rawSn: number;
  dark: boolean;
}

function InquiryForm({ rawSn, dark }: InquiryFormProps) {
  const pushToast = useUiStore((s) => s.pushToast);
  const {
    register,
    handleSubmit,
    reset,
    formState: { errors, isSubmitting },
  } = useForm<ContentForm>({
    resolver: zodResolver(contentSchema),
    defaultValues: { content: '' },
  });

  const { mutate } = useCreateInquiry(rawSn, {
    onSuccess: () => {
      pushToast({ variant: 'success', message: '문의가 등록되었습니다' });
      reset({ content: '' });
    },
    onError: () => pushToast({ variant: 'error', message: '문의 등록 실패' }),
  });

  const onSubmit = (values: ContentForm) => {
    mutate({ content: values.content });
  };

  return (
    <form
      onSubmit={handleSubmit(onSubmit)}
      className="flex flex-col gap-1.5"
      noValidate
      data-testid="inquiry-form"
    >
      <label htmlFor="inquiry-input" className="sr-only">
        문의 내용
      </label>
      <textarea
        id="inquiry-input"
        data-testid="inquiry-input"
        rows={2}
        maxLength={1000}
        placeholder="검수자에게 문의를 남기세요"
        className={cn(
          'w-full resize-none rounded border px-2 py-1.5 text-body',
          dark
            ? 'border-gray-700 bg-gray-900 text-gray-100 placeholder:text-gray-500'
            : 'border-border bg-white text-primary',
        )}
        {...register('content')}
      />
      {errors.content && (
        <p data-testid="inquiry-error" className="text-sub text-danger" role="alert">
          {errors.content.message}
        </p>
      )}
      <div className="flex justify-end">
        <button
          type="submit"
          data-testid="inquiry-submit"
          disabled={isSubmitting}
          className="inline-flex items-center rounded bg-primary-600 px-3 py-1 text-sub font-medium text-white hover:bg-primary-700 disabled:bg-primary-300"
        >
          문의 등록
        </button>
      </div>
    </form>
  );
}

interface ThreadCardProps {
  thread: IssueThread;
  rawSn: number;
  mode: IssueThreadMode;
  cardBorder: string;
  textBase: string;
  subText: string;
  dark: boolean;
}

function ThreadCard({
  thread,
  rawSn,
  mode,
  cardBorder,
  textBase,
  subText,
  dark,
}: ThreadCardProps) {
  const pushToast = useUiStore((s) => s.pushToast);
  // 409 충돌 시 인라인 안내 (토스트는 페이지 외부 Toaster 의존 — 패널 자체 안내도 제공).
  // 서버 최신 상태 동기화(invalidate)는 useAddIssueComment/useResolveIssue 훅이 onError(409)에서 수행 —
  // 컴포넌트는 충돌 메시지 상태만 관리하여 쿼리 키 의존을 두지 않는다.
  const [conflictMsg, setConflictMsg] = useState<string | null>(null);
  const locked = isCommentLocked(thread);
  const showResolve =
    mode === 'reviewer' &&
    thread.issueTypeCd === ISSUE_TYPE.INQUIRY &&
    thread.issueSttsCd !== ISSUE_STATUS.RESOLVED;

  const {
    register,
    handleSubmit,
    reset,
    formState: { errors, isSubmitting },
  } = useForm<ContentForm>({
    resolver: zodResolver(contentSchema),
    defaultValues: { content: '' },
  });

  const { mutate: addComment } = useAddIssueComment(rawSn, {
    onSuccess: () => reset({ content: '' }),
    onError: (err) => handleConflict(err),
  });

  const { mutate: resolve } = useResolveIssue(rawSn, {
    onSuccess: () =>
      pushToast({ variant: 'success', message: '문의를 해소했습니다' }),
    onError: (err) => handleConflict(err),
  });

  function handleConflict(err: unknown) {
    if (err instanceof ApiError && err.status === 409) {
      const msg = '다른 사용자가 먼저 처리했습니다';
      setConflictMsg(msg);
      pushToast({ variant: 'warning', message: msg });
      // 서버 최신 상태 동기화(issueThreads invalidate)는 mutation 훅이 내부에서 수행.
      return;
    }
    pushToast({ variant: 'error', message: '처리에 실패했습니다' });
  }

  const onSubmit = (values: ContentForm) => {
    addComment({ issueSn: thread.issueSn, body: { content: values.content } });
  };

  return (
    <div
      className={cn('flex flex-col gap-2 rounded border p-3', cardBorder)}
      data-testid={`issue-thread-card-${thread.issueSn}`}
    >
      <div className="flex items-center gap-2">
        <span
          className={cn(
            'inline-flex items-center rounded-full px-2 py-0.5 text-sub font-medium',
            thread.issueTypeCd === ISSUE_TYPE.REJECTION
              ? 'bg-red-100 text-red-700'
              : 'bg-blue-100 text-blue-700',
          )}
          data-testid={`issue-type-badge-${thread.issueSn}`}
        >
          {ISSUE_TYPE_LABEL[thread.issueTypeCd]}
        </span>
        <span
          className={cn(
            'inline-flex items-center rounded-full px-2 py-0.5 text-sub font-medium',
            thread.issueSttsCd === ISSUE_STATUS.RESOLVED
              ? 'bg-green-100 text-green-700'
              : thread.issueSttsCd === ISSUE_STATUS.ANSWERED
                ? 'bg-purple-100 text-purple-700'
                : 'bg-gray-100 text-gray-600',
          )}
          data-testid={`issue-status-badge-${thread.issueSn}`}
        >
          {ISSUE_STATUS_LABEL[thread.issueSttsCd]}
        </span>
        {thread.comments.length > 0 && (
          <span className={cn('text-sub', subText)}>
            댓글 {thread.comments.length}
          </span>
        )}
      </div>

      {/* 본문 — 텍스트 노드만 (XSS 방어). 개행 보존. */}
      <p className={cn('whitespace-pre-wrap break-words text-body', textBase)}>
        {thread.reason}
      </p>

      {thread.comments.length > 0 && (
        <ul className="flex flex-col gap-1.5">
          {thread.comments.map((c) => (
            <li
              key={c.commentSn}
              className={cn(
                'rounded px-2 py-1.5',
                dark ? 'bg-gray-800' : 'bg-bgLight',
              )}
            >
              <div className={cn('flex items-center gap-1.5 text-sub', subText)}>
                <span className="font-medium">{c.authorRoleCd}</span>
                <span>{formatDateTime(c.regDt)}</span>
              </div>
              <p className={cn('whitespace-pre-wrap break-words text-body', textBase)}>
                {c.content}
              </p>
            </li>
          ))}
        </ul>
      )}

      {conflictMsg && (
        <p
          data-testid={`thread-conflict-${thread.issueSn}`}
          className="text-sub text-amber-700"
          role="status"
        >
          {conflictMsg}
        </p>
      )}

      <form
        onSubmit={handleSubmit(onSubmit)}
        className="flex flex-col gap-1.5"
        noValidate
      >
        {locked && (
          <p
            className={cn('text-sub', subText)}
            data-testid={`thread-locked-${thread.issueSn}`}
          >
            해소됨 — 더 이상 댓글을 남길 수 없습니다.
          </p>
        )}
        <label htmlFor={`comment-input-${thread.issueSn}`} className="sr-only">
          댓글 입력
        </label>
        <textarea
          id={`comment-input-${thread.issueSn}`}
          data-testid={`comment-input-${thread.issueSn}`}
          rows={2}
          maxLength={1000}
          disabled={locked}
          placeholder={locked ? '해소된 문의입니다' : '댓글을 입력하세요'}
          className={cn(
            'w-full resize-none rounded border px-2 py-1.5 text-body disabled:opacity-50',
            dark
              ? 'border-gray-700 bg-gray-900 text-gray-100 placeholder:text-gray-500'
              : 'border-border bg-white text-primary',
          )}
          {...register('content')}
        />
        {errors.content && (
          <p
            data-testid={`comment-error-${thread.issueSn}`}
            className="text-sub text-danger"
            role="alert"
          >
            {errors.content.message}
          </p>
        )}
        {!locked && (
          <div className="flex justify-end gap-2">
            {showResolve && (
              <button
                type="button"
                data-testid={`resolve-button-${thread.issueSn}`}
                onClick={() => resolve(thread.issueSn)}
                className="inline-flex items-center rounded border border-green-600 px-3 py-1 text-sub font-medium text-green-700 hover:bg-green-50"
              >
                해소
              </button>
            )}
            <button
              type="submit"
              data-testid={`comment-submit-${thread.issueSn}`}
              disabled={isSubmitting}
              className="inline-flex items-center rounded bg-primary-600 px-3 py-1 text-sub font-medium text-white hover:bg-primary-700 disabled:bg-primary-300"
            >
              댓글
            </button>
          </div>
        )}
      </form>
    </div>
  );
}
