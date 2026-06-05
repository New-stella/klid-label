import { cn } from '@/lib/cn';

import { formatDateTime } from '../formatDateTime';
import { ISSUE_STATUS_LABEL, ISSUE_TYPE_LABEL } from '../issueLabels';
import type { IssueStatus, IssueType, ReviewIssue } from '../types';
import { ISSUE_STATUS, ISSUE_TYPE } from '../types';

export interface IssueCardProps {
  issue: ReviewIssue;
  /**
   * Phase 2 — 통합 스레드용 선택 뱃지. 미지정 시(기존 IssueSidebar 사용처) 렌더 생략 → 회귀 없음.
   */
  issueType?: IssueType;
  issueStatus?: IssueStatus;
  commentCount?: number;
}

/**
 * 검수 이슈 카드 (UI/UX §4-9 — 프레임별 카드 누적).
 *
 * 보안: description/뱃지 라벨은 React가 자동 escape (XSS 방어).
 */
export function IssueCard({ issue, issueType, issueStatus, commentCount }: IssueCardProps) {
  return (
    <div
      className="flex flex-col gap-1 rounded border border-border bg-white p-3 shadow-sm"
      data-testid={`issue-card-${issue.id}`}
    >
      {(issueType || issueStatus || commentCount !== undefined) && (
        <div className="flex items-center gap-2">
          {issueType && (
            <span
              data-testid={`issue-card-type-${issue.id}`}
              className={cn(
                'inline-flex items-center rounded-full px-2 py-0.5 text-sub font-medium',
                issueType === ISSUE_TYPE.REJECTION
                  ? 'bg-red-100 text-red-700'
                  : 'bg-blue-100 text-blue-700',
              )}
            >
              {ISSUE_TYPE_LABEL[issueType]}
            </span>
          )}
          {issueStatus && (
            <span
              data-testid={`issue-card-status-${issue.id}`}
              className={cn(
                'inline-flex items-center rounded-full px-2 py-0.5 text-sub font-medium',
                issueStatus === ISSUE_STATUS.RESOLVED
                  ? 'bg-green-100 text-green-700'
                  : issueStatus === ISSUE_STATUS.ANSWERED
                    ? 'bg-purple-100 text-purple-700'
                    : 'bg-gray-100 text-gray-600',
              )}
            >
              {ISSUE_STATUS_LABEL[issueStatus]}
            </span>
          )}
          {commentCount !== undefined && commentCount > 0 && (
            <span className="text-sub text-neutral" data-testid={`issue-card-comments-${issue.id}`}>
              댓글 {commentCount}
            </span>
          )}
        </div>
      )}
      <span className="text-sub font-medium text-primary">
        프레임 #{issue.frameId}
      </span>
      <p className="text-body text-primary whitespace-pre-wrap break-words">
        {issue.description}
      </p>
      <span className="text-sub text-neutral">
        {formatDateTime(issue.createdAt)}
      </span>
    </div>
  );
}
