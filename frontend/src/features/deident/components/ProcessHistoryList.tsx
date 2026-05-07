import { StatusBadge } from '@/components/common/StatusBadge';
import { cn } from '@/lib/cn';

import type { DeidentProcessHistoryItem, DeidentStatus } from '../types';

export interface ProcessHistoryListProps {
  items: DeidentProcessHistoryItem[];
  className?: string;
}

const STATUS_BADGE: Record<DeidentStatus, 'PENDING' | 'IN_PROGRESS' | 'COMPLETED' | 'BATCH_FAILED'> = {
  PENDING: 'PENDING',
  IN_PROGRESS: 'IN_PROGRESS',
  COMPLETED: 'COMPLETED',
  FAILED: 'BATCH_FAILED',
};

function formatDate(iso: string): string {
  try {
    return new Date(iso).toLocaleString('ko-KR');
  } catch {
    return iso;
  }
}

/**
 * SCR-DEIDENT-002 처리 이력 목록 — 시도 횟수, 상태, 메시지, 소요시간.
 */
export function ProcessHistoryList({ items, className }: ProcessHistoryListProps) {
  if (items.length === 0) {
    return (
      <section
        data-testid="process-history-list"
        aria-label="처리 이력"
        className={cn('rounded border border-border bg-white p-4', className)}
      >
        <h3 className="mb-2 text-section-title text-primary">처리 이력</h3>
        <p className="text-sub text-neutral">처리 이력이 없습니다.</p>
      </section>
    );
  }
  return (
    <section
      data-testid="process-history-list"
      aria-label="처리 이력"
      className={cn('rounded border border-border bg-white p-4', className)}
    >
      <h3 className="mb-3 text-section-title text-primary">처리 이력</h3>
      <ul className="flex flex-col gap-2">
        {items.map((it) => (
          <li
            key={it.attemptNo}
            data-testid={`process-history-${it.attemptNo}`}
            className="flex flex-wrap items-center gap-2 rounded border border-border p-2"
          >
            <span className="text-body font-medium text-primary">#{it.attemptNo}</span>
            <StatusBadge status={STATUS_BADGE[it.status]} />
            <span className="text-sub text-neutral">{formatDate(it.attemptedAt)}</span>
            {it.durationMs !== undefined && (
              <span className="text-sub text-neutral">
                ({(it.durationMs / 1000).toFixed(1)}s)
              </span>
            )}
            {it.message && (
              <span className="basis-full text-sub text-neutral">{it.message}</span>
            )}
          </li>
        ))}
      </ul>
    </section>
  );
}
