import { Badge } from '../ui/Badge';
import type { BatchStatus } from '../../api/types';

type AnyStatus = BatchStatus | 'PENDING' | 'IN_PROGRESS' | 'REVIEW_PENDING' | 'COMPLETED' | 'REJECTED' | 'REVIEW' | 'BATCH_COMPLETED' | string;

interface StatusBadgeProps {
  status: AnyStatus;
  size?: 'sm' | 'md';
}

const STATUS_CONFIG: Record<string, { tone: 'success' | 'info' | 'neutral' | 'danger' | 'warning' | 'purple'; label: string }> = {
  // BatchStatus
  COMPLETED: { tone: 'success', label: '완료' },
  PROCESSING: { tone: 'info', label: '처리중' },
  PENDING: { tone: 'neutral', label: '대기' },
  FAILED: { tone: 'danger', label: '실패' },
  // Task status
  IN_PROGRESS: { tone: 'info', label: '작업중' },
  REVIEW_PENDING: { tone: 'warning', label: '검수대기' },
  REJECTED: { tone: 'danger', label: '반려' },
  REVIEW: { tone: 'purple', label: '검수중' },
  BATCH_COMPLETED: { tone: 'neutral', label: '배치완료' },
  // Review status
  IN_REVIEW: { tone: 'purple', label: '검수중' },
  APPROVED: { tone: 'success', label: '승인' },
};

export function StatusBadge({ status, size = 'sm' }: StatusBadgeProps): JSX.Element {
  const config = STATUS_CONFIG[status] ?? { tone: 'neutral' as const, label: status };
  return (
    <Badge tone={config.tone} size={size}>
      {config.label}
    </Badge>
  );
}

export default StatusBadge;
