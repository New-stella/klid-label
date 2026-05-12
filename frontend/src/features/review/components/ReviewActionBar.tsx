// SCR-REVIEW-002 Phase 2 — 검수 화면 하단 액션 바.
//
// 우측 정렬 [반려][승인] 버튼 + 비활성 안내 텍스트.
// 실제 mutation 호출은 부모(ReviewPage)에서 처리, 본 컴포넌트는 콜백만 노출.

import { Button } from '@/components/common/Button';

import type { ReviewStatus } from '../types';

export interface ReviewActionBarProps {
  status: ReviewStatus;
  onApprove: () => void;
  onReject: () => void;
  isPending?: boolean;
}

/**
 * status 가 REVIEW_PENDING / REVIEWING 일 때만 액션 enabled.
 * COMPLETED / REJECTED 는 두 버튼 모두 disabled + 안내 텍스트.
 */
export function ReviewActionBar({
  status,
  onApprove,
  onReject,
  isPending = false,
}: ReviewActionBarProps) {
  const isFinal = status === 'COMPLETED' || status === 'REJECTED';
  const disabled = isFinal || isPending;

  const finalMessage =
    status === 'COMPLETED'
      ? '이미 승인 처리된 검수입니다.'
      : status === 'REJECTED'
        ? '이미 반려 처리된 검수입니다.'
        : null;

  return (
    <div
      className="flex h-14 shrink-0 items-center justify-end gap-3 border-t border-gray-700 bg-gray-900 px-4"
      data-testid="review-action-bar"
    >
      {finalMessage && (
        <span className="text-xs text-gray-400" data-testid="review-action-bar-message">
          {finalMessage}
        </span>
      )}
      <Button
        variant="danger"
        onClick={onReject}
        disabled={disabled}
        aria-label="반려"
        data-testid="review-action-reject"
      >
        반려
      </Button>
      <Button
        variant="primary"
        onClick={onApprove}
        disabled={disabled}
        loading={isPending}
        aria-label="승인"
        data-testid="review-action-approve"
      >
        승인
      </Button>
    </div>
  );
}
