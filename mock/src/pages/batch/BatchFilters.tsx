import { useState, useCallback } from 'react';
import { Search, RotateCcw } from 'lucide-react';
import { Button } from '../../components/ui/Button';

export interface BatchFilterValues {
  q: string;
  status: string;
  eventType: string;
  dateFrom: string;
  dateTo: string;
}

interface BatchFiltersProps {
  values: BatchFilterValues;
  onChange: (v: BatchFilterValues) => void;
  onReset: () => void;
}

const STATUSES = [
  { value: '', label: '전체 상태' },
  { value: 'COMPLETED', label: '완료' },
  { value: 'PROCESSING', label: '처리중' },
  { value: 'PENDING', label: '대기' },
  { value: 'FAILED', label: '실패' },
];

const EVENT_TYPES = [
  { value: '', label: '전체 이벤트' },
  { value: '쓰러짐', label: '쓰러짐' },
  { value: '폭력', label: '폭력' },
  { value: '교통사고', label: '교통사고' },
  { value: '이상행동(유괴)', label: '이상행동(유괴)' },
  { value: '침수', label: '침수' },
  { value: '산불', label: '산불' },
];

export const DEFAULT_FILTERS: BatchFilterValues = {
  q: '',
  status: '',
  eventType: '',
  dateFrom: '',
  dateTo: '',
};

export function BatchFilters({ values, onChange, onReset }: BatchFiltersProps) {
  const [local, setLocal] = useState<BatchFilterValues>(values);

  const handleSubmit = useCallback(
    (e: React.FormEvent) => {
      e.preventDefault();
      onChange(local);
    },
    [local, onChange],
  );

  const handleReset = useCallback(() => {
    setLocal(DEFAULT_FILTERS);
    onReset();
  }, [onReset]);

  return (
    <form
      onSubmit={handleSubmit}
      className="bg-white border border-gray-200 rounded-lg px-4 py-3 flex flex-wrap items-end gap-3 shadow-sm"
    >
      {/* 검색 */}
      <div className="flex flex-col gap-1 min-w-[180px] flex-1">
        <label className="text-xs font-medium text-gray-500">CCTV명 / 영상ID</label>
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

      {/* 이벤트 유형 */}
      <div className="flex flex-col gap-1">
        <label className="text-xs font-medium text-gray-500">이벤트 유형</label>
        <select
          value={local.eventType}
          onChange={(e) => setLocal((p) => ({ ...p, eventType: e.target.value }))}
          className="py-1.5 px-2 text-sm border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-primary-500"
        >
          {EVENT_TYPES.map((et) => (
            <option key={et.value} value={et.value}>
              {et.label}
            </option>
          ))}
        </select>
      </div>

      {/* 날짜 범위 */}
      <div className="flex flex-col gap-1">
        <label className="text-xs font-medium text-gray-500">시작일</label>
        <input
          type="date"
          value={local.dateFrom}
          onChange={(e) => setLocal((p) => ({ ...p, dateFrom: e.target.value }))}
          className="py-1.5 px-2 text-sm border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-primary-500"
        />
      </div>
      <div className="flex flex-col gap-1">
        <label className="text-xs font-medium text-gray-500">종료일</label>
        <input
          type="date"
          value={local.dateTo}
          onChange={(e) => setLocal((p) => ({ ...p, dateTo: e.target.value }))}
          className="py-1.5 px-2 text-sm border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-primary-500"
        />
      </div>

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

export default BatchFilters;
