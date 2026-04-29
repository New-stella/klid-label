import { useState, useCallback } from 'react';
import { Search, RotateCcw } from 'lucide-react';
import { Button } from '../../components/ui/Button';
import type { UserDto } from '../../api/types';

export interface TaskFilterValues {
  q: string;
  status: string;
  assigneeId: string;
}

interface TaskFiltersProps {
  values: TaskFilterValues;
  onChange: (v: TaskFilterValues) => void;
  onReset: () => void;
  showAssigneeSelect: boolean;
  workers: UserDto[];
}

const STATUSES = [
  { value: '', label: '전체 상태' },
  { value: 'PENDING', label: '대기' },
  { value: 'IN_PROGRESS', label: '진행중' },
  { value: 'REVIEW_PENDING', label: '검수대기' },
  { value: 'COMPLETED', label: '완료' },
  { value: 'REJECTED', label: '반려' },
];

export const DEFAULT_TASK_FILTERS: TaskFilterValues = {
  q: '',
  status: '',
  assigneeId: '',
};

export function TaskFilters({ values, onChange, onReset, showAssigneeSelect, workers }: TaskFiltersProps) {
  const [local, setLocal] = useState<TaskFilterValues>(values);

  // Sync external reset
  const handleSubmit = useCallback(
    (e: React.FormEvent) => {
      e.preventDefault();
      onChange(local);
    },
    [local, onChange],
  );

  const handleReset = useCallback(() => {
    setLocal(DEFAULT_TASK_FILTERS);
    onReset();
  }, [onReset]);

  return (
    <form
      onSubmit={handleSubmit}
      className="bg-white border border-gray-200 rounded-lg px-4 py-3 flex flex-wrap items-end gap-3 shadow-sm"
    >
      {/* 검색 */}
      <div className="flex flex-col gap-1 min-w-[180px] flex-1">
        <label className="text-xs font-medium text-gray-500">영상명 / 작업자명</label>
        <div className="relative">
          <Search size={14} className="absolute left-2.5 top-1/2 -translate-y-1/2 text-gray-400" />
          <input
            type="text"
            value={local.q}
            onChange={(e) => setLocal((p) => ({ ...p, q: e.target.value }))}
            placeholder="검색어 입력"
            className="w-full pl-8 pr-3 py-1.5 text-sm border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-primary-500"
          />
        </div>
      </div>

      {/* 상태 */}
      <div className="flex flex-col gap-1">
        <label className="text-xs font-medium text-gray-500">상태</label>
        <select
          value={local.status}
          onChange={(e) => setLocal((p) => ({ ...p, status: e.target.value }))}
          className="py-1.5 px-2 text-sm border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-primary-500"
        >
          {STATUSES.map((s) => (
            <option key={s.value} value={s.value}>
              {s.label}
            </option>
          ))}
        </select>
      </div>

      {/* 작업자 드롭다운 (REVIEWER만) */}
      {showAssigneeSelect && (
        <div className="flex flex-col gap-1">
          <label className="text-xs font-medium text-gray-500">작업자</label>
          <select
            value={local.assigneeId}
            onChange={(e) => setLocal((p) => ({ ...p, assigneeId: e.target.value }))}
            className="py-1.5 px-2 text-sm border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-primary-500"
          >
            <option value="">전체 작업자</option>
            {workers.map((w) => (
              <option key={w.id} value={w.id}>
                {w.name}
              </option>
            ))}
          </select>
        </div>
      )}

      {/* 버튼 */}
      <div className="flex gap-2 items-end">
        <Button type="submit" variant="primary" size="sm" leftIcon={Search}>
          조회
        </Button>
        <Button type="button" variant="secondary" size="sm" leftIcon={RotateCcw} onClick={handleReset}>
          초기화
        </Button>
      </div>
    </form>
  );
}

export default TaskFilters;
