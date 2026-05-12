import { useState } from 'react';
import { BarChart2, CheckCircle, Clock, XCircle, Tag } from 'lucide-react';

import { ErrorState } from '@/components/common/ErrorState';
import { KpiCard } from '@/components/common/KpiCard';
import { Skeleton } from '@/components/common/Skeleton';
import { DailyCompletionChart } from '@/features/stat/components/DailyCompletionChart';
import { useWorkerStat } from '@/features/stat/hooks/useWorkerStat';
import { useUsers } from '@/features/user/hooks/useUsers';
import { Role } from '@/lib/api/types';
import { useAuthStore } from '@/stores/useAuthStore';

/**
 * SCR-STAT-001 작업자 통계 (mock WorkerStats 정합).
 *
 * - REVIEWER: 작업자 선택 드롭다운으로 임의 작업자 통계 조회
 * - WORKER: 본인 통계 고정 (claims.sub)
 * - KPI 4 (완료/진행중/반려/총 라벨 수) + Secondary 2 (오토라벨 비율/반려율)
 * - 일별 작업량 (최근 30일) + 월별 통계 (최근 12개월)
 */
export function WorkerStatPage() {
  const claims = useAuthStore((s) => s.claims);
  const isReviewer = claims?.role === Role.REVIEWER;
  const myId = claims?.sub;

  // REVIEWER만 작업자 목록 로드 (selector)
  const { data: workersPage } = useUsers(
    isReviewer ? { role: Role.WORKER, size: 100 } : { size: 0 },
  );
  const workers = isReviewer ? workersPage?.content ?? [] : [];

  const [selectedWorkerId, setSelectedWorkerId] = useState<string | null>(null);

  const targetWorkerId: string | number | undefined = isReviewer
    ? selectedWorkerId ?? workers[0]?.id
    : myId;

  const { data, isLoading, error } = useWorkerStat(targetWorkerId);

  return (
    <section className="flex flex-col gap-6" data-testid="worker-stat-page">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-3">
          <div className="flex h-9 w-9 items-center justify-center rounded-lg bg-blue-50">
            <BarChart2 size={20} className="text-blue-600" aria-hidden />
          </div>
          <div>
            <h1 className="text-xl font-bold text-gray-900">작업자 통계</h1>
            {data?.workerName && (
              <p className="mt-0.5 text-xs text-gray-400">{data.workerName}</p>
            )}
          </div>
        </div>

        {isReviewer && workers.length > 0 && (
          <select
            value={selectedWorkerId ?? String(workers[0]?.id ?? '')}
            onChange={(e) => setSelectedWorkerId(e.target.value)}
            className="rounded-lg border border-gray-300 bg-white px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-primary-500"
            aria-label="작업자 선택"
          >
            {workers.map((w) => (
              <option key={w.id} value={String(w.id)}>
                {w.name}
              </option>
            ))}
          </select>
        )}
      </div>

      {error && <ErrorState title="통계 정보를 불러올 수 없습니다" />}

      {/* KPI 4개 */}
      <div
        data-testid="worker-kpi-grid"
        className="grid grid-cols-2 gap-4 md:grid-cols-4"
      >
        {isLoading ? (
          Array.from({ length: 4 }).map((_, i) => (
            <Skeleton key={i} height={84} className="w-full" />
          ))
        ) : (
          <>
            <KpiCard
              label="완료 작업"
              value={data?.completed ?? 0}
              icon={<CheckCircle className="h-4 w-4 text-green-500" aria-hidden />}
            />
            <KpiCard
              label="진행 중"
              value={data?.inProgress ?? 0}
              icon={<Clock className="h-4 w-4 text-yellow-500" aria-hidden />}
            />
            <KpiCard
              label="반려"
              value={data?.rejected ?? 0}
              icon={<XCircle className="h-4 w-4 text-red-500" aria-hidden />}
            />
            <KpiCard
              label="총 라벨 수"
              value={data?.labelCount ?? 0}
              icon={<Tag className="h-4 w-4 text-blue-500" aria-hidden />}
            />
          </>
        )}
      </div>

      {/* Secondary KPIs (오토라벨 비율 / 반려율) */}
      {data && (() => {
        const autoLabelPct = Number.isFinite(data.autoLabelRate) ? data.autoLabelRate * 100 : null;
        const rejectPct = Number.isFinite(data.rejectRate) ? data.rejectRate * 100 : null;
        return (
          <div className="grid grid-cols-2 gap-4">
            <div className="rounded-lg border border-gray-200 bg-white px-5 py-4">
              <p className="mb-1 text-xs text-gray-500">오토라벨 비율</p>
              <p className="text-xl font-bold tabular-nums text-gray-900">
                {autoLabelPct === null ? '—' : `${autoLabelPct.toFixed(1)}%`}
              </p>
            </div>
            <div className="rounded-lg border border-gray-200 bg-white px-5 py-4">
              <p className="mb-1 text-xs text-gray-500">반려율</p>
              <p
                className={[
                  'text-xl font-bold tabular-nums',
                  rejectPct !== null && rejectPct > 10 ? 'text-red-600' : 'text-gray-900',
                ].join(' ')}
              >
                {rejectPct === null ? '—' : `${rejectPct.toFixed(1)}%`}
              </p>
            </div>
          </div>
        );
      })()}

      {/* 일별 작업량 차트 */}
      <section className="rounded-lg border border-gray-200 bg-white p-5">
        <h2 className="mb-4 text-sm font-semibold text-gray-700">
          일별 작업량 (최근 30일)
        </h2>
        <DailyCompletionChart data={data?.dailyCompletion ?? []} />
      </section>

      {/* 월별 통계 표 */}
      <section className="overflow-hidden rounded-lg border border-gray-200 bg-white">
        <div className="border-b border-gray-100 px-5 py-4">
          <h2 className="text-sm font-semibold text-gray-700">
            월별 통계 (최근 12개월)
          </h2>
        </div>
        <table className="min-w-full text-body" data-testid="worker-monthly-table">
          <thead>
            <tr className="border-b border-border text-sub text-neutral">
              <th className="px-3 py-2 text-left">월</th>
              <th className="px-3 py-2 text-right">완료</th>
              <th className="px-3 py-2 text-right">반려</th>
              <th className="px-3 py-2 text-right">라벨 수</th>
            </tr>
          </thead>
          <tbody>
            {(data?.monthly ?? []).map((m) => (
              <tr key={m.month} className="border-b border-border">
                <td className="px-3 py-2">{m.month}</td>
                <td className="px-3 py-2 text-right tabular-nums">
                  {m.completed.toLocaleString('ko-KR')}
                </td>
                <td className="px-3 py-2 text-right tabular-nums text-red-500">
                  {m.rejected.toLocaleString('ko-KR')}
                </td>
                <td className="px-3 py-2 text-right tabular-nums">
                  {m.labelCount.toLocaleString('ko-KR')}
                </td>
              </tr>
            ))}
            {(!data?.monthly || data.monthly.length === 0) && (
              <tr>
                <td colSpan={4} className="px-3 py-4 text-center text-sub text-neutral">
                  월별 데이터가 없습니다
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </section>
    </section>
  );
}
