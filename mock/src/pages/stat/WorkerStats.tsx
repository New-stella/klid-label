import { useState } from 'react';
import { BarChart2, CheckCircle, Clock, XCircle, Tag } from 'lucide-react';
import { useFetch } from '../../api/queries';
import type { WorkerStat, OverallStat } from '../../api/types';
import { StatCard } from '../../components/ui/StatCard';
import { Table } from '../../components/ui/Table';
import type { ColumnDef } from '../../components/ui/Table';
import { Skeleton } from '../../components/ui/Skeleton';
import { SimpleBarChart } from '../../components/charts/SimpleBarChart';
import { useSessionStore } from '../../store/sessionStore';

interface MonthlyRow {
  month: string;
  completed: number;
  rejected: number;
  labelCount: number;
}

function buildMonthlyRows(workerId: string, overall: OverallStat): MonthlyRow[] {
  // Simulate monthly data from daily counts with a seed based on workerId
  const months = Array.from({ length: 12 }, (_, i) => {
    const d = new Date();
    d.setMonth(d.getMonth() - (11 - i));
    return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`;
  });

  const workerIdx = overall.workers.findIndex((w) => w.workerId === workerId);
  const seed = workerIdx >= 0 ? workerIdx : 0;

  return months.map((month, i) => ({
    month,
    completed: 10 + ((seed * 7 + i * 13) % 80),
    rejected: (seed * 3 + i * 5) % 8,
    labelCount: 200 + ((seed * 17 + i * 31) % 800),
  }));
}

const MONTHLY_COLS: ColumnDef<MonthlyRow>[] = [
  { key: 'month', header: '월' },
  { key: 'completed', header: '완료', render: (r) => <span className="tabular-nums font-medium">{r.completed}</span> },
  { key: 'rejected', header: '반려', render: (r) => <span className="tabular-nums text-red-500">{r.rejected}</span> },
  { key: 'labelCount', header: '라벨 수', render: (r) => <span className="tabular-nums">{r.labelCount.toLocaleString()}</span> },
];

export function WorkerStats() {
  const { currentRole, currentUser } = useSessionStore();
  const isReviewer = currentRole === 'REVIEWER';

  // Fetch overall to get worker list + daily data
  const { data: overall, isLoading: loadingOverall } = useFetch<OverallStat>('/stats/overall');

  // Selected worker — WORKER role is fixed to themselves
  const [selectedWorkerId, setSelectedWorkerId] = useState<string | null>(null);

  const workerId = isReviewer
    ? (selectedWorkerId ?? overall?.workers[0]?.workerId ?? null)
    : currentUser.id;

  const { data: workerStatRaw, isLoading: loadingWorker } = useFetch<WorkerStat | WorkerStat[]>(
    '/stats/worker',
    workerId ? { workerId } : undefined,
  );

  const workerStat: WorkerStat | null = Array.isArray(workerStatRaw)
    ? (workerStatRaw[0] ?? null)
    : (workerStatRaw ?? null);

  const isLoading = loadingOverall || loadingWorker;

  // Build daily bar data (30일) from overall.dailyCounts with worker-specific variation
  const dailyBarData = (overall?.dailyCounts ?? []).map((d) => {
    const workerIdx = overall?.workers.findIndex((w) => w.workerId === workerId) ?? 0;
    const seed = workerIdx >= 0 ? workerIdx : 0;
    const variation = 0.3 + (seed * 0.1);
    return {
      label: d.date,
      value: Math.round(d.count * variation),
    };
  });

  const monthlyRows = overall && workerId ? buildMonthlyRows(workerId, overall) : [];

  if (isLoading) {
    return (
      <div className="p-6 space-y-6">
        <Skeleton height="2rem" width="30%" />
        <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
          {Array.from({ length: 4 }).map((_, i) => <Skeleton key={i} height="6rem" />)}
        </div>
        <Skeleton height="16rem" />
        <Skeleton height="20rem" />
      </div>
    );
  }

  return (
    <div className="p-6 space-y-6">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-3">
          <div className="flex items-center justify-center w-9 h-9 rounded-lg bg-blue-50">
            <BarChart2 size={20} className="text-blue-600" />
          </div>
          <div>
            <h1 className="text-xl font-bold text-gray-900">작업자 통계</h1>
            {workerStat && (
              <p className="text-xs text-gray-400 mt-0.5">{workerStat.workerName}</p>
            )}
          </div>
        </div>

        {/* Worker selector (REVIEWER only) */}
        {isReviewer && overall && (
          <select
            value={selectedWorkerId ?? overall.workers[0]?.workerId ?? ''}
            onChange={(e) => setSelectedWorkerId(e.target.value)}
            className="text-sm border border-gray-300 rounded-lg px-3 py-2 bg-white focus:outline-none focus:ring-2 focus:ring-primary-500"
            aria-label="작업자 선택"
          >
            {overall.workers.map((w) => (
              <option key={w.workerId} value={w.workerId}>
                {w.workerName}
              </option>
            ))}
          </select>
        )}
      </div>

      {/* KPI 4개 */}
      {workerStat ? (
        <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
          <StatCard
            label="완료 작업"
            value={workerStat.completed}
            icon={CheckCircle}
            tone="success"
          />
          <StatCard
            label="진행 중"
            value={workerStat.inProgress}
            icon={Clock}
            tone="warning"
          />
          <StatCard
            label="반려"
            value={workerStat.rejected}
            icon={XCircle}
            tone="danger"
          />
          <StatCard
            label="총 라벨 수"
            value={workerStat.labelCount.toLocaleString()}
            icon={Tag}
            tone="primary"
          />
        </div>
      ) : (
        <div className="text-sm text-gray-400 p-4">통계 데이터가 없습니다.</div>
      )}

      {/* Secondary KPIs */}
      {workerStat && (
        <div className="grid grid-cols-2 gap-4">
          <div className="bg-white border border-gray-200 rounded-lg px-5 py-4">
            <p className="text-xs text-gray-500 mb-1">오토라벨 비율</p>
            <p className="text-xl font-bold text-gray-900 tabular-nums">
              {(workerStat.autoLabelRate * 100).toFixed(1)}%
            </p>
          </div>
          <div className="bg-white border border-gray-200 rounded-lg px-5 py-4">
            <p className="text-xs text-gray-500 mb-1">반려율</p>
            <p className={[
              'text-xl font-bold tabular-nums',
              workerStat.rejectRate > 0.1 ? 'text-red-600' : 'text-gray-900',
            ].join(' ')}>
              {(workerStat.rejectRate * 100).toFixed(1)}%
            </p>
          </div>
        </div>
      )}

      {/* 일별 작업량 차트 (30일) */}
      <div className="bg-white border border-gray-200 rounded-lg p-5">
        <h2 className="text-sm font-semibold text-gray-700 mb-4">일별 작업량 (최근 30일)</h2>
        {dailyBarData.length > 0 ? (
          <SimpleBarChart
            data={dailyBarData}
            height={200}
            xAxisInterval={5}
            color="#3b82f6"
          />
        ) : (
          <p className="text-sm text-gray-400 text-center py-8">데이터가 없습니다.</p>
        )}
      </div>

      {/* 월별 통계 테이블 (12개월) */}
      <div className="bg-white border border-gray-200 rounded-lg overflow-hidden">
        <div className="px-5 py-4 border-b border-gray-100">
          <h2 className="text-sm font-semibold text-gray-700">월별 통계 (최근 12개월)</h2>
        </div>
        <Table
          columns={MONTHLY_COLS}
          rows={monthlyRows}
          rowKey={(r) => r.month}
          emptyMessage="월별 데이터가 없습니다."
        />
      </div>
    </div>
  );
}

export default WorkerStats;
