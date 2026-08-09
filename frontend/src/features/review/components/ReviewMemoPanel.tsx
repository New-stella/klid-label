// SCR-REVIEW-002 Phase 6 — 검수 메모 통합 패널 (우측 aside 하단).
//
// 구성 (위→아래):
//   1) 검수 메모 헤더 + 이슈 추가 모드 토글 버튼
//   2) 이슈 목록 — BE 등록 이슈 + 로컬 pending 이슈 (이슈 추가 모드에서 누적)
//   3) 첨부파일 — placeholder ("첨부파일 기능 준비 중")
//   4) 검수 의견 textarea — 0/200 글자 카운터 (store 의 reviewComment 와 연동)
//
// 보안:
// - 모든 텍스트는 React 자동 escape (XSS 방어).
// - dangerouslySetInnerHTML 절대 금지.
//
// a11y:
// - 이슈 추가 모드 버튼: aria-pressed
// - textarea: label 연결 (Textarea 컴포넌트 내부 처리)

import { Button } from '@/components/common/Button';
import { EmptyState } from '@/components/common/EmptyState';
import { Textarea } from '@/components/common/Textarea';

import { useReviewSelectionStore } from '../store/useReviewSelectionStore';
import type { ReviewIssue } from '../types';

export interface ReviewMemoPanelProps {
  /** 영상 id — 향후 BE issue 추가 호출 시 사용 (현재는 표시 전용) */
  videoId: number;
  /** 이미 BE에 등록된 이슈 (useReviewIssues 의 결과) */
  issues: ReviewIssue[];
}

const REVIEW_COMMENT_MAX = 200;

/**
 * 검수 메모 / 이슈 / 첨부 / 의견 통합 패널.
 *
 * 이슈 추가 모드 토글은 store(useReviewSelectionStore)와 연동되며,
 * 모드 ON 상태에서 캔버스의 라벨을 클릭하면 LabelCanvas 가 자동으로
 * pendingIssue 를 추가한다 (텍스트는 카드에서 사용자가 수정 가능).
 */
export function ReviewMemoPanel({ videoId: _videoId, issues }: ReviewMemoPanelProps) {
  const issueMode = useReviewSelectionStore((s) => s.issueMode);
  const toggleIssueMode = useReviewSelectionStore((s) => s.toggleIssueMode);
  const reviewComment = useReviewSelectionStore((s) => s.reviewComment);
  const setReviewComment = useReviewSelectionStore((s) => s.setReviewComment);
  const pendingIssues = useReviewSelectionStore((s) => s.pendingIssues);
  const updatePendingIssue = useReviewSelectionStore((s) => s.updatePendingIssue);
  const removePendingIssue = useReviewSelectionStore((s) => s.removePendingIssue);

  const hasIssues = issues.length > 0 || pendingIssues.length > 0;

  return (
    <div
      className="flex flex-col gap-4 border-t border-gray-200 p-4"
      data-testid="review-memo-panel"
    >
      {/* 1) 검수 메모 헤더 + 이슈 추가 모드 토글 */}
      <header className="flex items-center justify-between">
        <h3 className="text-label font-semibold uppercase tracking-wide text-gray-500">
          검수 메모
        </h3>
        <Button
          variant={issueMode ? 'primary' : 'outline'}
          size="sm"
          onClick={toggleIssueMode}
          aria-pressed={issueMode}
          aria-label="이슈 추가 모드"
          data-testid="issue-mode-toggle"
        >
          {issueMode ? '이슈 추가 모드 (ON)' : '+ 이슈 추가 모드'}
        </Button>
      </header>

      {/* 2) 이슈 목록 */}
      <section
        aria-labelledby="memo-issues-heading"
        data-testid="memo-issues"
        className="flex flex-col gap-2"
      >
        <h4 id="memo-issues-heading" className="text-sub font-medium text-gray-700">
          이슈 목록 ({issues.length + pendingIssues.length}건)
        </h4>
        {!hasIssues ? (
          <div data-testid="memo-issues-empty">
            <EmptyState message="등록된 이슈가 없습니다" />
          </div>
        ) : (
          <div className="flex flex-col gap-2">
            {issues.map((issue) => (
              <article
                key={`be-${issue.id}`}
                className="flex flex-col gap-1 rounded border border-gray-200 bg-gray-50 p-3"
                data-testid={`memo-issue-be-${issue.id}`}
              >
                <span className="text-sub font-medium text-primary-700">
                  프레임 #{issue.frameId}
                </span>
                <p className="text-body whitespace-pre-wrap break-words text-gray-900">
                  {issue.description}
                </p>
                <span className="text-sub text-gray-600">
                  {new Date(issue.createdAt).toLocaleString('ko-KR')}
                </span>
              </article>
            ))}
            {pendingIssues.map((p, idx) => (
              <article
                key={`pending-${idx}`}
                className="flex flex-col gap-1 rounded border border-warning/60 bg-gray-50 p-3"
                data-testid={`memo-issue-pending-${idx}`}
              >
                <div className="flex items-center justify-between">
                  {/* 미저장(주의) 상태 강조 — KRDS warning 토큰 */}
                  <span className="text-sub font-medium text-warning-700">
                    {p.labelId != null ? `라벨 #${p.labelId} (미저장)` : '신규 이슈 (미저장)'}
                  </span>
                  <Button
                    variant="ghost"
                    size="sm"
                    className="px-1 py-0.5 hover:text-danger-700"
                    onClick={() => removePendingIssue(idx)}
                    aria-label={`이슈 삭제 ${idx + 1}`}
                    data-testid={`memo-issue-pending-remove-${idx}`}
                  >
                    삭제
                  </Button>
                </div>
                <Textarea
                  value={p.text}
                  maxLength={1000}
                  onChange={(e) => updatePendingIssue(idx, e.target.value)}
                  aria-label={`이슈 내용 ${idx + 1}`}
                  className="min-h-[72px] resize-y px-2 py-1.5"
                  data-testid={`memo-issue-pending-text-${idx}`}
                />
                <span className="text-sub text-gray-600">
                  {new Date(p.ts).toLocaleString('ko-KR')}
                </span>
              </article>
            ))}
          </div>
        )}
      </section>

      {/* 3) 첨부파일 placeholder */}
      <section
        aria-labelledby="memo-attach-heading"
        data-testid="memo-attach"
        className="flex flex-col gap-2"
      >
        <h4 id="memo-attach-heading" className="text-sub font-medium text-gray-700">
          첨부파일
        </h4>
        <div
          className="flex flex-col items-center justify-center gap-2 rounded border border-dashed border-gray-300 bg-gray-50 px-3 py-4 text-center"
          data-testid="memo-attach-placeholder"
        >
          <p className="text-sub text-gray-600">첨부파일 기능 준비 중</p>
          <Button variant="outline" size="sm" disabled aria-label="파일 첨부 (준비 중)">
            파일 첨부
          </Button>
        </div>
      </section>

      {/* 4) 검수 의견 textarea */}
      <section
        aria-labelledby="memo-comment-heading"
        data-testid="memo-comment"
        className="flex flex-col gap-2"
      >
        <div className="flex items-center justify-between">
          <h4 id="memo-comment-heading" className="text-sub font-medium text-gray-700">
            <label htmlFor="review-memo-comment-textarea">검수 의견</label>
          </h4>
          <span className="text-sub text-gray-500" data-testid="memo-comment-counter">
            {reviewComment.length}/{REVIEW_COMMENT_MAX}
          </span>
        </div>
        {/* 라벨은 상단 헤더(카운터와 같은 줄)에 있으므로 label prop 대신 id 로 연결한다. */}
        <Textarea
          id="review-memo-comment-textarea"
          maxLength={REVIEW_COMMENT_MAX}
          value={reviewComment}
          onChange={(e) => setReviewComment(e.target.value)}
          placeholder="검수 전반에 대한 의견을 입력하세요 (최대 200자)"
          className="min-h-[100px] resize-y"
          data-testid="memo-comment-textarea"
        />
      </section>
    </div>
  );
}
