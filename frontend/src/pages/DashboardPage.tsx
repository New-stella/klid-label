import { ErrorState } from '@/components/common/ErrorState';
import { KpiCard } from '@/components/common/KpiCard';
import { PageHeader } from '@/components/common/PageHeader';
import { Skeleton } from '@/components/common/Skeleton';
import { EventDistributionGrid } from '@/features/dashboard/components/EventDistributionGrid';
import { MyTaskCard } from '@/features/dashboard/components/MyTaskCard';
import { NoticeCard } from '@/features/dashboard/components/NoticeCard';
import { useDashboardSummary } from '@/features/dashboard/hooks/useDashboardSummary';
import { Role } from '@/lib/api/types';
import { useAuthStore } from '@/stores/useAuthStore';

/**
 * SCR-DASH-001 메인 대시보드.
 * - WORKER: KPI 4개 (처리 대기 / 처리 완료 / 내 작업 / 반려 건수)
 * - REVIEWER: KPI 3개 (처리 대기 / 처리 완료 / 반려 건수) — 내 작업 KPI 제외
 * - 누적 카드 2종(이미지 10만, 영상 5천 목표)
 * - 6종 이벤트 분포 그리드 (고정)
 * - 내 작업(WORKER 전용) + 공지사항
 */
export function DashboardPage() {
  const { data, isLoading, error } = useDashboardSummary();
  const role = useAuthStore((s) => s.claims?.role);
  const isWorker = role === Role.WORKER;

  return (
    <section className="flex flex-col gap-4">
      <PageHeader
        title="대시보드"
        description="저작도구 전체 현황 요약"
      />

      {error && <ErrorState title="대시보드 정보를 불러올 수 없습니다" />}

      {/* KPI — 역할별 차등 */}
      <div
        data-testid="dashboard-kpi-grid"
        data-role={role}
        className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-4"
      >
        {isLoading ? (
          Array.from({ length: isWorker ? 4 : 3 }).map((_, i) => (
            <Skeleton key={i} height={84} className="w-full" />
          ))
        ) : (
          <>
            <KpiCard
              label="처리 대기"
              value={data?.pendingCount ?? 0}
              unit="건"
            />
            <KpiCard
              label="처리 완료"
              value={data?.completedCount ?? 0}
              unit="건"
            />
            {isWorker && (
              <KpiCard
                label="내 작업"
                value={data?.myTaskCount ?? 0}
                unit="건"
              />
            )}
            <KpiCard
              label="반려 건수"
              value={data?.rejectedCount ?? 0}
              unit="건"
            />
          </>
        )}
      </div>

      {/* 누적 카드 2종 */}
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
        <KpiCard
          label="누적 이미지 (목표 100,000)"
          value={data?.cumulativeImageCount ?? 0}
          unit="장"
        />
        <KpiCard
          label="누적 영상 (목표 5,000)"
          value={data?.cumulativeVideoCount ?? 0}
          unit="건"
        />
      </div>

      {/* 6종 이벤트 분포 — 고정 */}
      <section aria-label="이벤트 분포 영역">
        <h2 className="mb-2 text-section-title text-primary">이벤트 분포</h2>
        <EventDistributionGrid data={data?.eventDistribution ?? []} />
      </section>

      {/* 내 작업(WORKER 전용) + 공지 */}
      <div className="grid grid-cols-1 gap-3 lg:grid-cols-2">
        {isWorker && data?.myTask && <MyTaskCard task={data.myTask} />}
        <NoticeCard notices={data?.notices ?? []} />
      </div>
    </section>
  );
}
