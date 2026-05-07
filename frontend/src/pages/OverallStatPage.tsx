import { useState } from 'react';

import { Button } from '@/components/common/Button';
import { ErrorState } from '@/components/common/ErrorState';
import { KpiCard } from '@/components/common/KpiCard';
import { PageHeader } from '@/components/common/PageHeader';
import { Skeleton } from '@/components/common/Skeleton';
import { EventDistributionGrid } from '@/features/dashboard/components/EventDistributionGrid';
import { downloadReport } from '@/features/stat/api';
import { WorkerStatsTable } from '@/features/stat/components/WorkerStatsTable';
import { useOverallStat } from '@/features/stat/hooks/useOverallStat';
import { useUiStore } from '@/stores/useUiStore';

/**
 * SCR-STAT-002 전체 구축 현황 — REVIEWER 전용.
 *
 * 회귀 방지 (UI/UX §4-11):
 * - 누적 카드 2개는 KpiCard만 사용 — ProgressBar 절대 미노출
 * - KpiCard 컴포넌트 props에 progress 없음 → 구조적으로 표시 불가
 *
 * 구성:
 * - 누적 카드 2 (이미지 / 영상 — 진행률 미노출)
 * - 처리 현황 5 카드
 * - 이벤트 분포 6종 (EventDistributionGrid 재사용)
 * - 작업자 통계 표
 * - [📥 리포트 다운로드] 버튼
 */
export function OverallStatPage() {
  const { data, isLoading, error } = useOverallStat();
  const pushToast = useUiStore((s) => s.pushToast);
  const [downloading, setDownloading] = useState(false);

  const handleDownload = async () => {
    setDownloading(true);
    try {
      const blob = await downloadReport('MONTH');
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      // 보안: 파일명은 사용자 입력 미반영 — 고정 prefix + ISO 날짜
      a.download = `overall-stat-${new Date().toISOString().slice(0, 10)}.csv`;
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      URL.revokeObjectURL(url);
      pushToast({ variant: 'success', message: '리포트 다운로드를 시작했습니다' });
    } catch {
      pushToast({ variant: 'error', message: '리포트 다운로드에 실패했습니다' });
    } finally {
      setDownloading(false);
    }
  };

  return (
    <section className="flex flex-col gap-4">
      <PageHeader
        title="전체 구축 현황"
        description="REVIEWER 전용 — 누적 / 처리 현황 / 이벤트 분포 / 작업자 통계"
        actions={
          <Button
            variant="primary"
            onClick={handleDownload}
            loading={downloading}
            data-testid="download-report-btn"
          >
            📥 리포트 다운로드
          </Button>
        }
      />

      {error && <ErrorState title="전체 통계를 불러올 수 없습니다" />}

      {/* 누적 카드 2 — ProgressBar 미노출 (UI/UX §4-11 회귀 방지) */}
      <div
        data-testid="cumulative-cards"
        className="grid grid-cols-1 gap-3 sm:grid-cols-2"
      >
        {isLoading ? (
          Array.from({ length: 2 }).map((_, i) => (
            <Skeleton key={i} height={84} className="w-full" />
          ))
        ) : (
          <>
            <KpiCard
              label="누적 이미지"
              value={data?.cumulativeImageCount ?? 0}
              unit="장"
            />
            <KpiCard
              label="누적 영상"
              value={data?.cumulativeVideoCount ?? 0}
              unit="건"
            />
          </>
        )}
      </div>

      {/* 처리 현황 5 카드 */}
      <section aria-label="처리 현황">
        <h2 className="mb-2 text-section-title text-primary">처리 현황</h2>
        <div
          data-testid="processing-cards"
          className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-5"
        >
          {isLoading ? (
            Array.from({ length: 5 }).map((_, i) => (
              <Skeleton key={i} height={84} className="w-full" />
            ))
          ) : (
            <>
              <KpiCard label="대기" value={data?.processing.pending ?? 0} unit="건" />
              <KpiCard label="진행중" value={data?.processing.inProgress ?? 0} unit="건" />
              <KpiCard
                label="검수 대기"
                value={data?.processing.reviewPending ?? 0}
                unit="건"
              />
              <KpiCard label="승인" value={data?.processing.approved ?? 0} unit="건" />
              <KpiCard label="반려" value={data?.processing.rejected ?? 0} unit="건" />
            </>
          )}
        </div>
      </section>

      {/* 이벤트 분포 6종 */}
      <section aria-label="이벤트 분포">
        <h2 className="mb-2 text-section-title text-primary">이벤트 분포</h2>
        <EventDistributionGrid data={data?.eventDistribution ?? []} />
      </section>

      {/* 작업자 통계 */}
      <section aria-label="작업자 통계">
        <h2 className="mb-2 text-section-title text-primary">작업자 통계</h2>
        <WorkerStatsTable rows={data?.workers ?? []} loading={isLoading} />
      </section>
    </section>
  );
}
