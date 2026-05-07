import { cn } from '@/lib/cn';

export type BadgeStatus =
  | 'BATCH_PROCESSING'
  | 'BATCH_COMPLETED'
  | 'BATCH_FAILED'
  | 'PENDING'
  | 'IN_PROGRESS'
  | 'REVIEW_PENDING'
  | 'REVIEWING'
  | 'COMPLETED'
  | 'REJECTED';

export interface StatusBadgeProps {
  status: BadgeStatus;
  label?: string;
  className?: string;
}

// UI/UX §3.4 9종 상태 — §2.2 색상 토큰 매핑
const statusConfig: Record<BadgeStatus, { label: string; className: string }> = {
  BATCH_PROCESSING: {
    label: '배치 처리중',
    className: 'bg-accent/10 text-accent border border-accent',
  },
  BATCH_COMPLETED: {
    label: '배치 완료',
    className: 'bg-success/10 text-success border border-success',
  },
  BATCH_FAILED: {
    label: '배치 실패',
    className: 'bg-danger/10 text-danger border border-danger',
  },
  PENDING: {
    label: '대기',
    className: 'bg-bgLight text-neutral border border-border',
  },
  IN_PROGRESS: {
    label: '진행중',
    className: 'bg-secondary/10 text-secondary border border-secondary',
  },
  REVIEW_PENDING: {
    label: '검수 대기',
    className: 'bg-warning/10 text-warning border border-warning',
  },
  REVIEWING: {
    label: '검수중',
    className: 'bg-accent/10 text-accent border border-accent',
  },
  COMPLETED: {
    label: '완료',
    className: 'bg-success/10 text-success border border-success',
  },
  REJECTED: {
    label: '반려',
    className: 'bg-danger/10 text-danger border border-danger',
  },
};

export function StatusBadge({ status, label, className }: StatusBadgeProps) {
  const cfg = statusConfig[status];
  return (
    <span
      data-status={status}
      className={cn(
        'inline-flex items-center rounded-full px-2 py-0.5 text-sub font-medium',
        cfg.className,
        className,
      )}
    >
      {label ?? cfg.label}
    </span>
  );
}
