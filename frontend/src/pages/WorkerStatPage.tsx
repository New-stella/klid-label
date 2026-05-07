import { useState } from 'react';

import { ErrorState } from '@/components/common/ErrorState';
import { KpiCard } from '@/components/common/KpiCard';
import { PageHeader } from '@/components/common/PageHeader';
import { Skeleton } from '@/components/common/Skeleton';
import { DailyCompletionChart } from '@/features/stat/components/DailyCompletionChart';
import { EventTypePieChart } from '@/features/stat/components/EventTypePieChart';
import { useWorkerStat } from '@/features/stat/hooks/useWorkerStat';
import type { StatPeriod } from '@/features/stat/types';
import { useAuthStore } from '@/stores/useAuthStore';

const PERIOD_OPTIONS: { value: StatPeriod; label: string }[] = [
  { value: 'WEEK', label: '주간' },
  { value: 'MONTH', label: '월간' },
  { value: 'QUARTER', label: '분기' },
  { value: 'YEAR', label: '연간' },
];

/**
 * SCR-STAT-001 작업자 본인 통계.
 * - KPI 4개: 누적 라벨 / 누적 검수 / 승인률 / 평균 소요시간
 * - 일별 완료 바차트 + 이벤트 비율 파이차트
 * - 월별 표
 */
export function WorkerStatPage() {
  const claims = useAuthStore((s) => s.claims);
  const userId = Number(claims?.sub ?? 0);
  const [period, setPeriod] = useState<StatPeriod>('WEEK');
  const { data, isLoading, error } = useWorkerStat(userId, period);

  return (
    <section className="flex flex-col gap-4">
      <PageHeader title="내 통계" description="작업자 본인 통계 — KPI · 일별 · 이벤트별" />

      <div className="flex items-center gap-2" role="radiogroup" aria-label="기간 선택">
        {PERIOD_OPTIONS.map((opt) => (
          <button
            key={opt.value}
            type="button"
            role="radio"
            aria-checked={period === opt.value}
            onClick={() => setPeriod(opt.value)}
            className={
              period === opt.value
                ? 'rounded border border-primary bg-primary px-3 py-1 text-sub text-white'
                : 'rounded border border-border bg-white px-3 py-1 text-sub text-neutral hover:bg-bgLight'
            }
          >
            {opt.label}
          </button>
        ))}
      </div>

      {error && <ErrorState title="통계 정보를 불러올 수 없습니다" />}

      {/* KPI 4개 */}
      <div
        data-testid="worker-kpi-grid"
        className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-4"
      >
        {isLoading ? (
          Array.from({ length: 4 }).map((_, i) => (
            <Skeleton key={i} height={84} className="w-full" />
          ))
        ) : (
          <>
            <KpiCard label="누적 라벨" value={data?.totalLabeled ?? 0} unit="프레임" />
            <KpiCard label="누적 검수" value={data?.totalReviewed ?? 0} unit="건" />
            <KpiCard
              label="승인률"
              value={data ? `${data.approvalRate.toFixed(1)}%` : '0%'}
            />
            <KpiCard
              label="평균 소요시간"
              value={data ? `${(data.averageElapsedSec / 60).toFixed(1)}분` : '0분'}
            />
          </>
        )}
      </div>

      {/* 차트 2종 */}
      <div className="grid grid-cols-1 gap-3 lg:grid-cols-2">
        <section className="rounded border border-border bg-white p-4">
          <h2 className="mb-2 text-section-title text-primary">일별 완료</h2>
          <DailyCompletionChart data={data?.dailyCompletion ?? []} />
        </section>
        <section className="rounded border border-border bg-white p-4">
          <h2 className="mb-2 text-section-title text-primary">이벤트 비율</h2>
          <EventTypePieChart data={data?.eventDistribution ?? []} />
        </section>
      </div>

      {/* 월별 표 */}
      <section className="rounded border border-border bg-white p-4">
        <h2 className="mb-2 text-section-title text-primary">월별 통계</h2>
        <table className="min-w-full text-body" data-testid="worker-monthly-table">
          <thead>
            <tr className="border-b border-border text-sub text-neutral">
              <th className="px-3 py-2 text-left">월</th>
              <th className="px-3 py-2 text-right">라벨 프레임</th>
              <th className="px-3 py-2 text-right">검수 건수</th>
              <th className="px-3 py-2 text-right">승인률</th>
            </tr>
          </thead>
          <tbody>
            {(data?.monthly ?? []).map((m) => (
              <tr key={m.month} className="border-b border-border">
                <td className="px-3 py-2">{m.month}</td>
                <td className="px-3 py-2 text-right">{m.labeled.toLocaleString('ko-KR')}</td>
                <td className="px-3 py-2 text-right">{m.reviewed.toLocaleString('ko-KR')}</td>
                <td className="px-3 py-2 text-right">{m.approvalRate.toFixed(1)}%</td>
              </tr>
            ))}
            {(!data?.monthly || data.monthly.length === 0) && (
              <tr>
                <td colSpan={4} className="px-3 py-4 text-center text-sub text-neutral">
                  월별 통계가 없습니다
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </section>
    </section>
  );
}
