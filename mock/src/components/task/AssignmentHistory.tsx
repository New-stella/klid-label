import { X, History } from 'lucide-react';
import type { TaskDto } from '../../api/types';

interface HistoryEntry {
  id: number;
  date: string;
  actor: string;
  action: string;
  reason?: string;
}

function getMockHistory(task: TaskDto): HistoryEntry[] {
  // Placeholder history entries — 3~5 items per task
  const base: HistoryEntry[] = [
    {
      id: 1,
      date: task.createdAt.split('T')[0] ?? '2026-04-01',
      actor: '이검수',
      action: `최라벨 작업자에게 배정`,
    },
  ];

  if (task.status !== 'PENDING') {
    base.push({
      id: 2,
      date: task.updatedAt.split('T')[0] ?? '2026-04-10',
      actor: '이검수',
      action: '정작업으로 재배정',
      reason: '기존 작업자 일정 충돌',
    });
  }

  if (task.status === 'REVIEW_PENDING' || task.status === 'COMPLETED' || task.status === 'REJECTED') {
    base.push({
      id: 3,
      date: task.updatedAt.split('T')[0] ?? '2026-04-15',
      actor: task.assigneeName ?? '작업자',
      action: '검수 제출',
    });
  }

  if (task.status === 'REJECTED') {
    base.push({
      id: 4,
      date: task.updatedAt.split('T')[0] ?? '2026-04-16',
      actor: '이검수',
      action: '검수 반려',
      reason: '바운딩박스 정밀도 부족',
    });
  }

  if (task.status === 'COMPLETED') {
    base.push({
      id: 4,
      date: task.updatedAt.split('T')[0] ?? '2026-04-17',
      actor: '이검수',
      action: '검수 승인 완료',
    });
  }

  return base;
}

interface AssignmentHistoryProps {
  task: TaskDto | null;
  open: boolean;
  onClose: () => void;
}

export function AssignmentHistory({ task, open, onClose }: AssignmentHistoryProps) {
  if (!open || !task) return null;

  const entries = getMockHistory(task);

  return (
    <>
      {/* Backdrop */}
      <div
        className="fixed inset-0 z-40 bg-black/20"
        onClick={onClose}
        aria-hidden="true"
      />

      {/* Side panel */}
      <div className="fixed right-0 top-0 h-full w-80 z-50 bg-white shadow-2xl flex flex-col">
        {/* Header */}
        <div className="flex items-center justify-between px-5 py-4 border-b border-gray-100">
          <div className="flex items-center gap-2">
            <History size={16} className="text-gray-500" />
            <h3 className="text-sm font-semibold text-gray-900">배정 이력</h3>
          </div>
          <button
            onClick={onClose}
            className="p-1 rounded-md text-gray-400 hover:text-gray-600 hover:bg-gray-100 transition-colors"
            aria-label="닫기"
          >
            <X size={16} />
          </button>
        </div>

        {/* Task info */}
        <div className="px-5 py-3 bg-gray-50 border-b border-gray-100">
          <p className="text-xs font-medium text-gray-500">대상 작업</p>
          <p className="text-sm font-semibold text-gray-800 mt-0.5 truncate">{task.videoName}</p>
        </div>

        {/* Timeline */}
        <div className="flex-1 overflow-y-auto px-5 py-4">
          <ol className="relative border-l border-gray-200 space-y-6">
            {entries.map((entry) => (
              <li key={entry.id} className="ml-4">
                <div className="absolute -left-1.5 mt-1.5 h-3 w-3 rounded-full border-2 border-white bg-primary-400" />
                <time className="mb-1 block text-xs font-normal text-gray-400">{entry.date}</time>
                <p className="text-sm text-gray-800">
                  <span className="font-semibold text-gray-900">{entry.actor}</span>
                  {' — '}
                  {entry.action}
                </p>
                {entry.reason && (
                  <p className="text-xs text-gray-500 mt-0.5">사유: {entry.reason}</p>
                )}
              </li>
            ))}
          </ol>
        </div>
      </div>
    </>
  );
}

export default AssignmentHistory;
