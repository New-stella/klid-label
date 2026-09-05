import { zodResolver } from '@hookform/resolvers/zod';
import { useMemo, useState } from 'react';
import { useForm } from 'react-hook-form';
import { z } from 'zod';

import { Button } from '@/components/common/Button';
import { Textarea } from '@/components/common/Textarea';
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
import { ISSUE_STATUS_LABEL, ISSUE_TYPE_LABEL, issueAuthorLabel } from '../issueLabels';
import { ISSUE_STATUS, ISSUE_TYPE, type IssueThread } from '../types';

export type IssueThreadMode = 'worker' | 'reviewer';

export interface IssueThreadPanelProps {
  rawSn: number;
  /**
   * 'worker' = 라벨링 화면(WORKER), 'reviewer' = 검수 화면(REVIEWER).
   * 해소 버튼 노출 여부만 가른다 — 문의 등록·댓글은 두 화면 공통.
   */
  mode: IssueThreadMode;
}

// 패널 톤 (KRDS 토큰).
// @req R12 — 이 상수는 **두 표면**에 걸쳐 쓰인다: 흰 카드(스레드 헤더·본문)와
//   bg-bgLight(중립 50단) 댓글 항목. 500단 파랑은 흰 배경 4.55 로 간신히 통과하지만
//   bgLight 위에서는 4.17 로 AA 미달이라, 한 단 진한 600단으로 올려 두 표면을 함께
//   만족시킨다(흰 6.83 · bgLight 6.26). 상수를 쪼개 표면별로 다른 파랑을 쓰면 한 패널에
//   두 가지 톤이 섞이므로 상수 하나를 올린다.
const TEXT_BASE = 'text-primary-600';
const SUB_TEXT = 'text-neutral';
const CARD_BORDER = 'border-border bg-white';

// 본문 1~1000자 — RejectModal 선례와 동일 정책.
const contentSchema = z.object({
  content: z.string().min(1, '내용을 입력하세요').max(1000, '최대 1000자'),
});
type ContentForm = z.infer<typeof contentSchema>;

/**
 * INQUIRY 가 RESOLVED 면 댓글 입력 잠금. REJECTION 은 RESOLVED 여도 입력 가능 (BE 정책).
 */
function isCommentLocked(thread: IssueThread): boolean {
  return thread.issueTypeCd === ISSUE_TYPE.INQUIRY && thread.issueSttsCd === ISSUE_STATUS.RESOLVED;
}

/**
 * SCR-LABEL-001 / SCR-REVIEW-002 — 검수자↔작업자 이슈 스레드 패널.
 *
 * - 반려(REJECTION) 이력 + 문의(INQUIRY) 통합 스레드.
 * - 문의 등록·댓글은 역할 구분 없이 두 화면 공통. 해소는 REVIEWER 전용.
 * - 상태 전이는 서버 응답 신뢰 (클라 전이 금지) — mutation 후 issueThreads invalidate.
 *
 * 보안:
 * - 본문은 텍스트 노드로만 렌더 (dangerouslySetInnerHTML 절대 미사용 — XSS 방어).
 *   개행은 whitespace-pre-wrap.
 * - content 는 zod 1~1000자 검증 후 전송.
 */
export function IssueThreadPanel({ rawSn, mode }: IssueThreadPanelProps) {
  const { data, isLoading, error } = useIssueThreads(rawSn);
  const threads = useMemo(() => data ?? [], [data]);

  const unresolvedInquiryCount = useMemo(
    () =>
      threads.filter(
        (t) => t.issueTypeCd === ISSUE_TYPE.INQUIRY && t.issueSttsCd !== ISSUE_STATUS.RESOLVED,
      ).length,
    [threads],
  );

  return (
    <section
      className="flex flex-col gap-3 p-3"
      data-testid="issue-thread-panel"
      aria-label="이슈 스레드"
    >
      <header className="flex items-center justify-between">
        <h2 className={cn('text-section-title font-semibold', TEXT_BASE)}>이슈 스레드</h2>
        <span
          data-testid="unresolved-inquiry-count"
          className="inline-flex min-w-5 items-center justify-center rounded-full bg-danger/10 px-1.5 py-0.5 text-sub font-medium text-danger-700"
          aria-label={`미해소 문의 ${unresolvedInquiryCount}건`}
        >
          {unresolvedInquiryCount}
        </span>
      </header>

      {/* 문의 등록은 역할 구분 없이 노출 — 검수자도 문의를 등록할 수 있다. */}
      <InquiryForm rawSn={rawSn} />

      {isLoading ? (
        <p className={cn('text-sub', SUB_TEXT)}>이슈 로딩 중...</p>
      ) : error ? (
        <p className="text-sub text-danger">이슈를 불러오지 못했습니다.</p>
      ) : threads.length === 0 ? (
        <p className={cn('text-sub', SUB_TEXT)} data-testid="issue-thread-empty">
          등록된 이슈가 없습니다
        </p>
      ) : (
        <ul className="flex flex-col gap-3" data-testid="issue-thread-list">
          {threads.map((thread) => (
            <li key={thread.issueSn}>
              <ThreadCard thread={thread} rawSn={rawSn} mode={mode} />
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

interface InquiryFormProps {
  rawSn: number;
}

function InquiryForm({ rawSn }: InquiryFormProps) {
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
      <Textarea
        id="inquiry-input"
        data-testid="inquiry-input"
        maxLength={1000}
        placeholder="문의 내용을 입력하세요"
        className="min-h-[72px] resize-none px-2 py-1.5"
        {...register('content')}
      />
      {errors.content && (
        <p data-testid="inquiry-error" className="text-sub text-danger" role="alert">
          {errors.content.message}
        </p>
      )}
      <div className="flex justify-end">
        <Button
          type="submit"
          variant="primary"
          size="sm"
          data-testid="inquiry-submit"
          loading={isSubmitting}
        >
          문의 등록
        </Button>
      </div>
    </form>
  );
}

interface ThreadCardProps {
  thread: IssueThread;
  rawSn: number;
  mode: IssueThreadMode;
}

function ThreadCard({ thread, rawSn, mode }: ThreadCardProps) {
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
    onSuccess: () => pushToast({ variant: 'success', message: '문의를 해소했습니다' }),
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

  // 스레드 작성자 — 댓글 작성자와 **같은 헬퍼·같은 표기**("{이름} ({역할})").
  // 이름이 없으면 사번 폴백, 역할이 없으면(BE 미해석) 이름만. 둘 다 없으면 '' → falsy 로 미표시.
  // 검수자가 낸 문의와 작업자가 낸 문의를 이 표기로 구분한다.
  const reporterLabel = issueAuthorLabel(
    thread.reportedUserName,
    thread.reportedUserNo,
    thread.reportedUserRoleCd,
  );

  return (
    <div
      className={cn('flex flex-col gap-2 rounded border p-3', CARD_BORDER)}
      data-testid={`issue-thread-card-${thread.issueSn}`}
    >
      <div className="flex items-center gap-2">
        <span
          className={cn(
            'inline-flex items-center rounded-full px-2 py-0.5 text-sub font-medium',
            thread.issueTypeCd === ISSUE_TYPE.REJECTION
              ? 'bg-danger/10 text-danger-700'
              : 'bg-info/10 text-info-700',
          )}
          data-testid={`issue-type-badge-${thread.issueSn}`}
        >
          {ISSUE_TYPE_LABEL[thread.issueTypeCd]}
        </span>
        <span
          className={cn(
            'inline-flex items-center rounded-full px-2 py-0.5 text-sub font-medium',
            thread.issueSttsCd === ISSUE_STATUS.RESOLVED
              ? 'bg-success/10 text-success-700'
              : thread.issueSttsCd === ISSUE_STATUS.ANSWERED
                ? // 같은 '답변됨' 상태 배지다 — 톤 근거는 IssueCard 주석 참조.
                  // ⚠ 한쪽만 바꾸면 같은 상태가 화면마다 다른 색으로 읽힌다.
                  'bg-info/10 text-info-700'
                : 'bg-gray-100 text-gray-600',
          )}
          data-testid={`issue-status-badge-${thread.issueSn}`}
        >
          {ISSUE_STATUS_LABEL[thread.issueSttsCd]}
        </span>
        {reporterLabel && (
          <span
            className={cn('text-sub', SUB_TEXT)}
            data-testid={`thread-reporter-${thread.issueSn}`}
          >
            {reporterLabel}
          </span>
        )}
        {thread.comments.length > 0 && (
          <span className={cn('text-sub', SUB_TEXT)}>댓글 {thread.comments.length}</span>
        )}
      </div>

      {/* 본문 — 텍스트 노드만 (XSS 방어). 개행 보존. */}
      <p className={cn('whitespace-pre-wrap break-words text-body', TEXT_BASE)}>{thread.reason}</p>

      {thread.comments.length > 0 && (
        <ul className="flex flex-col gap-1.5">
          {thread.comments.map((c) => (
            <li key={c.commentSn} className="rounded bg-bgLight px-2 py-1.5">
              <div className={cn('flex items-center gap-1.5 text-sub', SUB_TEXT)}>
                {/* "{이름} ({역할})" — 이름 미해석 시 사번 폴백. 역할 코드값 노출 금지. */}
                <span className="font-medium">
                  {issueAuthorLabel(c.authorName, c.authorNo, c.authorRoleCd)}
                </span>
                <span>{formatDateTime(c.regDt)}</span>
              </div>
              <p className={cn('whitespace-pre-wrap break-words text-body', TEXT_BASE)}>
                {c.content}
              </p>
            </li>
          ))}
        </ul>
      )}

      {conflictMsg && (
        <p
          data-testid={`thread-conflict-${thread.issueSn}`}
          className="text-sub text-warning"
          role="status"
        >
          {conflictMsg}
        </p>
      )}

      <form onSubmit={handleSubmit(onSubmit)} className="flex flex-col gap-1.5" noValidate>
        {locked && (
          <p className={cn('text-sub', SUB_TEXT)} data-testid={`thread-locked-${thread.issueSn}`}>
            해소됨 — 더 이상 댓글을 남길 수 없습니다.
          </p>
        )}
        <label htmlFor={`comment-input-${thread.issueSn}`} className="sr-only">
          댓글 입력
        </label>
        <Textarea
          id={`comment-input-${thread.issueSn}`}
          data-testid={`comment-input-${thread.issueSn}`}
          maxLength={1000}
          disabled={locked}
          placeholder={locked ? '해소된 문의입니다' : '댓글을 입력하세요'}
          className="min-h-[72px] resize-none px-2 py-1.5 disabled:opacity-50"
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
              // outline 기본은 primary 톤 — '해소'는 success 의미라 색 토큰만 바꾼다(배경은 기본 bg-white).
              <Button
                variant="outline"
                size="sm"
                data-testid={`resolve-button-${thread.issueSn}`}
                onClick={() => resolve(thread.issueSn)}
                className="border-success text-success-700 hover:border-success hover:bg-success/10 active:bg-success/20"
              >
                해소
              </Button>
            )}
            <Button
              type="submit"
              variant="primary"
              size="sm"
              data-testid={`comment-submit-${thread.issueSn}`}
              loading={isSubmitting}
            >
              댓글
            </Button>
          </div>
        )}
      </form>
    </div>
  );
}
