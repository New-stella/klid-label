import { useState } from 'react';
import { TrendingUp, Image, Film, Activity } from 'lucide-react';
import { useFetch } from '../../api/queries';
import type { OverallStat, WorkerStat } from '../../api/types';
import { Table } from '../../components/ui/Table';
import type { ColumnDef } from '../../components/ui/Table';
import { ProgressBar } from '../../components/ui/ProgressBar';
import { Skeleton } from '../../components/ui/Skeleton';
import { SimplePieChart } from '../../components/charts/SimplePieChart';
import { SimpleBarChart } from '../../components/charts/SimpleBarChart';

type SortField = 'completed' | 'labelCount' | 'rejectRate' | 'autoLabelRate';
type SortDir = 'asc' | 'desc';

const EVENT_DIST = [
  { label: '쓰러짐', value: 240, color: '#a855f7' },
  { label: '폭력', value: 180, color: '#ef4444' },
  { label: '교통사고', value: 320, color: '#3b82f6' },
  { label: '이상행동(유괴)', value: 130, color: '#f59e0b' },
  { label: '침수', value: 150, color: '#06b6d4' },
  { label: '산불', value: 80, color: '#dc2626' },
];

function buildWorkerColumns(
  sortField: SortField,
  sortDir: SortDir,
  onSort: (f: SortField) => void,
): ColumnDef<WorkerStat>[] {
  const sortSuffix = (field: SortField) =>
    sortField === field ? (sortDir === 'desc' ? ' ↓' : ' ↑') : '';

  return [
    {
      key: 'workerName',
      header: '작업자',
      render: (r) => (
        <span className="font-medium text-gray-800">{r.workerName}</span>
      ),
    },
    {
      key: 'completed',
      header: `완료${sortSuffix('completed')}`,
      render: (r) => (
        <button
          type="button"
          className="tabular-nums font-medium text-green-700 hover:underline"
          onClick={() => onSort('completed')}
        >
          {r.completed}
        </button>
      ),
    },
    {
      key: 'inProgress',
      header: '진행',
      render: (r) => <span className="tabular-nums text-yellow-600">{r.inProgress}</span>,
    },
    {
      key: 'labelCount',
      header: `라벨${sortSuffix('labelCount')}`,
      render: (r) => (
        <button
          type="button"
          className="tabular-nums hover:underline"
          onClick={() => onSort('labelCount')}
        >
          {r.labelCount.toLocaleString()}
        </button>
      ),
    },
    {
      key: 'autoLabelRate',
      header: `오토라벨${sortSuffix('autoLabelRate')}`,
      render: (r) => (
        <button
          type="button"
          className="flex items-center gap-2 min-w-[80px] w-full"
          onClick={() => onSort('autoLabelRate')}
        >
          <ProgressBar value={r.autoLabelRate * 100} size="sm" tone="primary" className="flex-1" />
          <span className="text-xs tabular-nums text-gray-600 w-10 text-right">
            {(r.autoLabelRate * 100).toFixed(0)}%
          </span>
        </button>
      ),
    },
    {
      key: 'rejectRate',
      header: '반려율',
      render: (r) => (
        <span
          className={[
            'tabular-nums text-xs font-medium',
            r.rejectRate > 0.1 ? 'text-red-600' : 'text-gray-600',
          ].join(' ')}
        >
          {(r.rejectRate * 100).toFixed(1)}%
        </span>
      ),
    },
  ];
}

export function OverallStats() {
  const { data: overall, isLoading } = useFetch<OverallStat>('/stats/overall');
  const [sortField, setSortField] = useState<SortField>('completed');
  const [sortDir, setSortDir] = useState<SortDir>('desc');

  const handleSort = (field: SortField) => {
    if (sortField === field) {
      setSortDir((d) => (d === 'desc' ? 'asc' : 'desc'));
    } else {
      setSortField(field);
      setSortDir('desc');
    }
  };

  if (isLoading) {
    return (
      <div className="p-6 space-y-6">
        <Skeleton height="2rem" width="30%" />
        <div className="grid grid-cols-2 gap-4">
          {Array.from({ length: 2 }).map((_, i) => <Skeleton key={i} height="8rem" />)}
        </div>
        <Skeleton height="16rem" />
        <Skeleton height="24rem" />
      </div>
    );
  }

  if (!overall) return null;

  const imagePct = Math.round((overall.imageCompleted / overall.imageTarget) * 100);
  const videoPct = Math.round((overall.videoCompleted / overall.videoTarget) * 100);

  const sortedWorkers = [...overall.workers].sort((a, b) => {
    const aVal = a[sortField] as number;
    const bVal = b[sortField] as number;
    return sortDir === 'desc' ? bVal - aVal : aVal - bVal;
  });

  const workerColumns = buildWorkerColumns(sortField, sortDir, handleSort);

  // Batch status mock data
  const batchStats = {
    total: 5820,
    completed: 4210,
    processing: 340,
    failed: 82,
    pending: 1188,
  };

  return (
    <div className="p-6 space-y-6">
      {/* Header */}
      <div className="flex items-center gap-3">
        <div className="flex items-center justify-center w-9 h-9 rounded-lg bg-indigo-50">
          <TrendingUp size={20} className="text-indigo-600" />
        </div>
        <h1 className="text-xl font-bold text-gray-900">전체 구축 현황</h1>
      </div>

      {/* Progress cards — 이미지/영상 */}
      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
        {/* 이미지 */}
        <div className="bg-white border border-gray-200 rounded-lg p-6 space-y-4">
          <div className="flex items-center gap-2">
            <Image size={18} className="text-blue-500" />
            <h2 className="text-sm font-semibold text-gray-700">이미지 학습데이터</h2>
          </div>
          <div className="flex items-end justify-between">
            <div>
              <p className="text-3xl font-black text-gray-900 tabular-nums">
                {imagePct}%
              </p>
              <p className="text-xs text-gray-400 mt-1">
                {overall.imageCompleted.toLocaleString()} / {overall.imageTarget.toLocaleString()}장
              </p>
            </div>
            <div className="text-right">
              <p className="text-xs text-gray-500">목표</p>
              <p className="text-lg font-bold text-gray-700 tabular-nums">
                {(overall.imageTarget / 10000).toFixed(0)}만 장
              </p>
            </div>
          </div>
          <ProgressBar
            value={imagePct}
            tone={imagePct >= 80 ? 'success' : imagePct >= 50 ? 'primary' : 'warning'}
            size="md"
            showLabel
          />
        </div>

        {/* 영상 */}
        <div className="bg-white border border-gray-200 rounded-lg p-6 space-y-4">
          <div className="flex items-center gap-2">
            <Film size={18} className="text-purple-500" />
            <h2 className="text-sm font-semibold text-gray-700">영상 학습데이터</h2>
          </div>
          <div className="flex items-end justify-between">
            <div>
              <p className="text-3xl font-black text-gray-900 tabular-nums">
                {videoPct}%
              </p>
              <p className="text-xs text-gray-400 mt-1">
                {overall.videoCompleted.toLocaleString()} / {overall.videoTarget.toLocaleString()}건
              </p>
            </div>
            <div className="text-right">
              <p className="text-xs text-gray-500">목표</p>
              <p className="text-lg font-bold text-gray-700 tabular-nums">
                {overall.videoTarget.toLocaleString()} 건
              </p>
            </div>
          </div>
          <ProgressBar
            value={videoPct}
            tone={videoPct >= 80 ? 'success' : videoPct >= 50 ? 'primary' : 'warning'}
            size="md"
            showLabel
          />
        </div>
      </div>

      {/* 영상 처리 현황 카드 */}
      <div className="bg-white border border-gray-200 rounded-lg p-5">
        <div className="flex items-center gap-2 mb-4">
          <Activity size={16} className="text-gray-500" />
          <h2 className="text-sm font-semibold text-gray-700">처리 현황</h2>
        </div>
        <div className="grid grid-cols-5 gap-3">
          {[
            { label: '전체', value: batchStats.total, color: 'text-gray-800' },
            { label: '완료', value: batchStats.completed, color: 'text-green-600' },
            { label: '처리중', value: batchStats.processing, color: 'text-blue-600' },
            { label: '실패', value: batchStats.failed, color: 'text-red-600' },
            { label: '대기', value: batchStats.pending, color: 'text-yellow-600' },
          ].map((s) => (
            <div key={s.label} className="text-center bg-gray-50 rounded-lg p-3">
              <p className={['text-xl font-bold tabular-nums', s.color].join(' ')}>
                {s.value.toLocaleString()}
              </p>
              <p className="text-xs text-gray-500 mt-0.5">{s.label}</p>
            </div>
          ))}
        </div>
      </div>

      {/* 일별 전체 작업 추이 */}
      <div className="bg-white border border-gray-200 rounded-lg p-5">
        <h2 className="text-sm font-semibold text-gray-700 mb-4">일별 전체 작업량 (최근 30일)</h2>
        <SimpleBarChart
          data={overall.dailyCounts.map((d) => ({ label: d.date, value: d.count }))}
          height={200}
          xAxisInterval={5}
          color="#6366f1"
        />
      </div>

      {/* 이벤트 분포 */}
      <div className="bg-white border border-gray-200 rounded-lg p-5">
        <h2 className="text-sm font-semibold text-gray-700 mb-4">이벤트 유형 분포</h2>
        <div className="flex items-center gap-8">
          <SimplePieChart data={EVENT_DIST} size={160} showLegend />
          {/* Horizontal bar */}
          <div className="flex-1 space-y-2">
            {EVENT_DIST.map((e) => {
              const total = EVENT_DIST.reduce((s, d) => s + d.value, 0);
              const pct = (e.value / total) * 100;
              return (
                <div key={e.label} className="space-y-1">
                  <div className="flex items-center justify-between text-xs">
                    <span className="text-gray-600">{e.label}</span>
                    <span className="tabular-nums font-medium">{e.value}</span>
                  </div>
                  <div className="h-2 bg-gray-100 rounded-full overflow-hidden">
                    <div
                      className="h-full rounded-full transition-all"
                      style={{ width: `${pct}%`, backgroundColor: e.color }}
                    />
                  </div>
                </div>
              );
            })}
          </div>
        </div>
      </div>

      {/* 작업자별 테이블 */}
      <div className="bg-white border border-gray-200 rounded-lg overflow-hidden">
        <div className="px-5 py-4 border-b border-gray-100">
          <h2 className="text-sm font-semibold text-gray-700">작업자별 현황</h2>
          <p className="text-xs text-gray-400 mt-0.5">컬럼 헤더 클릭으로 정렬</p>
        </div>
        <Table
          columns={workerColumns}
          rows={sortedWorkers}
          rowKey={(r) => r.workerId}
          emptyMessage="작업자 데이터가 없습니다."
        />
      </div>
    </div>
  );
}

export default OverallStats;
