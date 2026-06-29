import { cn } from '@/lib/cn';

export type BadgeStatus =
  | 'BATCH_PROCESSING'
  | 'BATCH_COMPLETED'
  | 'BATCH_FAILED'
  | 'PENDING'
  | 'MARKING_READY'
  | 'IN_PROGRESS'
  | 'PROCESSING'
  | 'REVIEW_PENDING'
  | 'REVIEWING'
  | 'IN_REVIEW'
  | 'COMPLETED'
  | 'APPROVED'
  | 'REJECTED'
  | 'FAILED'
  | 'DEIDENT_IN_PROGRESS'
  | 'DEIDENT_FAILED';

export interface StatusBadgeProps {
  status: BadgeStatus | string;
  label?: string;
  className?: string;
}

// UI/UX §3.4 9종 상태 — mock modern blue tone (soft tonal pill) + BE alias
const statusConfig: Record<string, { label: string; className: string }> = {
  BATCH_PROCESSING: { label: '배치 처리중', className: 'bg-blue-100 text-blue-700' },
  BATCH_COMPLETED: { label: '배치 완료', className: 'bg-green-100 text-green-700' },
  BATCH_FAILED: { label: '배치 실패', className: 'bg-red-100 text-red-700' },
  PENDING: { label: '대기', className: 'bg-gray-100 text-gray-600' },
  MARKING_READY: { label: '마킹 대기', className: 'bg-blue-100 text-blue-700' },
  IN_PROGRESS: { label: '진행중', className: 'bg-blue-100 text-blue-700' },
  PROCESSING: { label: '처리중', className: 'bg-blue-100 text-blue-700' },
  REVIEW_PENDING: { label: '검수 대기', className: 'bg-yellow-100 text-yellow-700' },
  REVIEWING: { label: '검수중', className: 'bg-purple-100 text-purple-700' },
  IN_REVIEW: { label: '검수중', className: 'bg-purple-100 text-purple-700' },
  COMPLETED: { label: '완료', className: 'bg-green-100 text-green-700' },
  APPROVED: { label: '승인', className: 'bg-green-100 text-green-700' },
  REJECTED: { label: '반려', className: 'bg-red-100 text-red-700' },
  FAILED: { label: '실패', className: 'bg-red-100 text-red-700' },
  // Phase 3 — 비식별 처리 상태 (deidentStatus 기반). 진행중=정보(파랑), 실패=경고(빨강).
  DEIDENT_IN_PROGRESS: { label: '비식별 진행중', className: 'bg-blue-100 text-blue-700' },
  DEIDENT_FAILED: { label: '비식별 실패', className: 'bg-red-100 text-red-700' },
};

const FALLBACK = { label: '', className: 'bg-gray-100 text-gray-600' };

export function StatusBadge({ status, label, className }: StatusBadgeProps) {
  const cfg = statusConfig[status] ?? FALLBACK;
  return (
    <span
      data-status={status}
      className={cn(
        'inline-flex items-center rounded-full px-2 py-0.5 text-sub font-medium',
        cfg.className,
        className,
      )}
    >
      {label ?? cfg.label ?? status}
    </span>
  );
}
