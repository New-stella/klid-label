import { cn } from '@/lib/cn';
import type { MarkingResponse } from '../types';

interface MarkingListProps {
  markings: MarkingResponse[];
  onDelete: (markingSn: number) => void;
  deleting?: boolean;
  className?: string;
}

export function MarkingList({ markings, onDelete, deleting, className }: MarkingListProps) {
  if (markings.length === 0) {
    return (
      <div className={cn('text-sm text-gray-400 py-4 text-center', className)}>
        저장된 마킹이 없습니다.
      </div>
    );
  }

  return (
    <div className={cn('space-y-2', className)}>
      <h3 className="text-sm font-medium text-gray-700">저장된 마킹</h3>
      {markings.map((m) => (
        <div
          key={m.markingSn}
          className="flex items-center justify-between rounded border p-2 text-sm"
        >
          <div className="flex items-center gap-3">
            <span className="font-medium">{m.eventName}</span>
            <span
              className={cn(
                'rounded px-1.5 py-0.5 text-xs',
                m.markingMode === 'AUTO' ? 'bg-green-100 text-green-700' : 'bg-purple-100 text-purple-700',
              )}
            >
              {m.markingMode === 'AUTO' ? '자동' : '수동'}
            </span>
            <span className="text-gray-400">{m.marks.length}건</span>
            <span
              className={cn(
                'rounded px-1.5 py-0.5 text-xs',
                m.status === 'VLM_COMPLETED'
                  ? 'bg-blue-100 text-blue-700'
                  : m.status === 'VLM_REQUESTED'
                    ? 'bg-yellow-100 text-yellow-700'
                    : 'bg-gray-100 text-gray-600',
              )}
            >
              {m.status}
            </span>
          </div>
          <button
            type="button"
            onClick={() => onDelete(m.markingSn)}
            disabled={deleting}
            className="rounded px-2 py-1 text-xs text-red-600 hover:bg-red-50 transition-colors"
          >
            삭제
          </button>
        </div>
      ))}
    </div>
  );
}
