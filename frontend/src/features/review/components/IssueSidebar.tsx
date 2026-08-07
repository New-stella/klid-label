import { EmptyState } from '@/components/common/EmptyState';
import { Spinner } from '@/components/common/Spinner';

import { useReviewIssues } from '../hooks/useReviewIssues';
import { AddIssueButton } from './AddIssueButton';
import { IssueCard } from './IssueCard';

export interface IssueSidebarProps {
  reviewId: number;
  defaultFrameId?: number;
}

/**
 * 검수 이슈 사이드바 (UI/UX §4-9).
 * - 프레임별 카드 누적
 * - **캔버스 좌표 마커 절대 미사용** (회귀 방지 테스트 포함)
 * - 이슈는 텍스트(프레임 번호 + 설명)만으로 표현
 */
export function IssueSidebar({ reviewId, defaultFrameId }: IssueSidebarProps) {
  const { data, isLoading, error } = useReviewIssues(reviewId);

  return (
    <aside
      className="flex w-80 shrink-0 flex-col gap-3 border-l border-border bg-bgLight p-3"
      data-testid="issue-sidebar"
      aria-label="검수 이슈 사이드바"
    >
      <div className="flex items-center justify-between">
        <h2 className="text-section-title text-primary">이슈 목록</h2>
        <AddIssueButton reviewId={reviewId} defaultFrameId={defaultFrameId} />
      </div>

      {isLoading ? (
        <div className="flex justify-center py-6">
          <Spinner label="이슈 로딩" />
        </div>
      ) : error ? (
        <p className="text-sub text-danger-700">이슈를 불러오지 못했습니다.</p>
      ) : !data || data.length === 0 ? (
        <EmptyState message="등록된 이슈가 없습니다" />
      ) : (
        <div className="flex flex-col gap-2 overflow-y-auto" data-testid="issue-list">
          {data.map((issue) => (
            <IssueCard key={issue.id} issue={issue} />
          ))}
        </div>
      )}
    </aside>
  );
}
