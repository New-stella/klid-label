import { useEffect, useState, type FormEvent } from 'react';
import { RotateCcw, Search } from 'lucide-react';

import { Button } from '@/components/common/Button';
import type { Worker } from '@/features/task/types';

export interface TaskFilterValues {
  q: string;
  status: string;
  assigneeId: string;
  eventType: string;
}

export const DEFAULT_TASK_FILTERS: TaskFilterValues = {
  q: '',
  status: '',
  assigneeId: '',
  eventType: '',
};

interface TaskFiltersProps {
  values: TaskFilterValues;
  onChange: (v: TaskFilterValues) => void;
  onReset: () => void;
  /** REVIEWER만 작업자 select 노출 */
  showAssigneeSelect: boolean;
  workers: Worker[];
  /** 영상의 이벤트 유형 옵션 (videos에서 unique). */
  eventTypes: string[];
}

const STATUSES = [
  { value: '', label: '전체 상태' },
  { value: 'UNASSIGNED', label: '미배정' },
  { value: 'PENDING', label: '대기' },
  { value: 'IN_PROGRESS', label: '진행중' },
  { value: 'SUBMITTED', label: '검수대기' },
  { value: 'COMPLETED', label: '완료' },
  { value: 'REJECTED', label: '반려' },
];

/**
 * mock §4-5 정합 — 작업 목록 검색 폼.
 * 영상명/작업자명 input + 이벤트 select + 상태 select + (REVIEWER만) 작업자 select.
 *
 * 보안: 검색어는 부모에서 axios params로만 전달 — XSS/Injection 방지.
 */
export function TaskFilters({
  values,
  onChange,
  onReset,
  showAssigneeSelect,
  workers,
  eventTypes,
}: TaskFiltersProps) {
  const [local, setLocal] = useState<TaskFilterValues>(values);

  // 외부에서 reset이 일어나면 local도 초기화
  useEffect(() => {
    setLocal(values);
  }, [values]);

  const handleSubmit = (e: FormEvent) => {
    e.preventDefault();
    onChange(local);
  };

  const handleReset = () => {
    setLocal(DEFAULT_TASK_FILTERS);
    onReset();
  };

  return (
    <form
      onSubmit={handleSubmit}
      data-testid="task-filters"
      className="flex flex-wrap items-end gap-3 rounded-lg border border-gray-200 bg-white px-4 py-3 shadow-sm"
    >
      {/* 검색 */}
      <div className="flex min-w-[180px] flex-1 flex-col gap-1">
        <label
          htmlFor="task-filter-q"
          className="text-xs font-medium text-gray-500"
        >
          영상명 / 작업자명
        </label>
        <div className="relative">
          <Search
            size={14}
            className="absolute left-2.5 top-1/2 -translate-y-1/2 text-gray-400"
            aria-hidden
          />
          <input
            id="task-filter-q"
            type="text"
            value={local.q}
            onChange={(e) => setLocal((p) => ({ ...p, q: e.target.value }))}
            placeholder="검색어 입력"
            className="w-full rounded-md border border-gray-300 py-1.5 pl-8 pr-3 text-sm focus:outline-none focus:ring-2 focus:ring-primary-500"
          />
        </div>
      </div>

      {/* 이벤트 유형 */}
      <div className="flex flex-col gap-1">
        <label
          htmlFor="task-filter-event"
          className="text-xs font-medium text-gray-500"
        >
          이벤트
        </label>
        <select
          id="task-filter-event"
          value={local.eventType}
          onChange={(e) =>
            setLocal((p) => ({ ...p, eventType: e.target.value }))
          }
          className="rounded-md border border-gray-300 px-2 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-primary-500"
        >
          <option value="">전체</option>
          {eventTypes.map((et) => (
            <option key={et} value={et}>
              {et}
            </option>
          ))}
        </select>
      </div>

      {/* 상태 */}
      <div className="flex flex-col gap-1">
        <label
          htmlFor="task-filter-status"
          className="text-xs font-medium text-gray-500"
        >
          상태
        </label>
        <select
          id="task-filter-status"
          value={local.status}
          onChange={(e) =>
            setLocal((p) => ({ ...p, status: e.target.value }))
          }
          className="rounded-md border border-gray-300 px-2 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-primary-500"
        >
          {STATUSES.map((s) => (
            <option key={s.value} value={s.value}>
              {s.label}
            </option>
          ))}
        </select>
      </div>

      {/* 작업자 (REVIEWER 전용) */}
      {showAssigneeSelect && (
        <div className="flex flex-col gap-1">
          <label
            htmlFor="task-filter-assignee"
            className="text-xs font-medium text-gray-500"
          >
            작업자
          </label>
          <select
            id="task-filter-assignee"
            value={local.assigneeId}
            onChange={(e) =>
              setLocal((p) => ({ ...p, assigneeId: e.target.value }))
            }
            className="rounded-md border border-gray-300 px-2 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-primary-500"
          >
            <option key="__all__" value="">전체 작업자</option>
            {workers.map((w, idx) => (
              <option
                key={w.id ?? `worker-${idx}`}
                value={String(w.id ?? '')}
              >
                {w.name ?? '(이름 없음)'}
              </option>
            ))}
          </select>
        </div>
      )}

      <div className="flex items-end gap-2">
        <Button type="submit" variant="primary" size="sm">
          <Search size={14} aria-hidden />
          조회
        </Button>
        <Button
          type="button"
          variant="secondary"
          size="sm"
          onClick={handleReset}
        >
          <RotateCcw size={14} aria-hidden />
          초기화
        </Button>
      </div>
    </form>
  );
}

export default TaskFilters;
