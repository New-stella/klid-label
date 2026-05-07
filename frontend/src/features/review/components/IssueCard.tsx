import type { ReviewIssue } from '../types';

export interface IssueCardProps {
  issue: ReviewIssue;
}

/**
 * 검수 이슈 카드 (UI/UX §4-9 — 프레임별 카드 누적).
 *
 * 보안: description은 React가 자동 escape (XSS 방어).
 */
export function IssueCard({ issue }: IssueCardProps) {
  return (
    <div
      className="flex flex-col gap-1 rounded border border-border bg-white p-3 shadow-sm"
      data-testid={`issue-card-${issue.id}`}
    >
      <span className="text-sub font-medium text-primary">
        프레임 #{issue.frameId}
      </span>
      <p className="text-body text-primary whitespace-pre-wrap break-words">
        {issue.description}
      </p>
      <span className="text-sub text-neutral">
        {new Date(issue.createdAt).toLocaleString('ko-KR')}
      </span>
    </div>
  );
}
