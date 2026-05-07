import { ErrorState } from '@/components/common/ErrorState';
import { KpiCard } from '@/components/common/KpiCard';
import { PageHeader } from '@/components/common/PageHeader';
import { Skeleton } from '@/components/common/Skeleton';
import { EmptyState } from '@/components/common/EmptyState';
import { VideoStatusStepper } from '@/features/video/components/VideoStatusStepper';
import { useBatchStatus } from '@/features/video/hooks/useBatchStatus';

/**
 * SCR-VIDEO-002 처리 현황.
 * 5초 폴링 + 단계 표시(프레임/비식별/YOLO/SAM2/VLM) + 진행률 바.
 */
export function VideoStatusPage() {
  const { data, isLoading, error } = useBatchStatus();

  return (
    <section className="flex flex-col gap-4">
      <PageHeader
        title="처리 현황"
        description="배치 파이프라인의 실시간 처리 현황 (5초마다 자동 갱신)"
      />

      <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
        <KpiCard
          label="처리 중"
          value={data?.totalProcessing ?? 0}
          unit="건"
        />
        <KpiCard
          label="완료"
          value={data?.totalCompleted ?? 0}
          unit="건"
        />
        <KpiCard
          label="실패"
          value={data?.totalFailed ?? 0}
          unit="건"
        />
      </div>

      {error && <ErrorState title="배치 현황을 불러올 수 없습니다" />}

      {isLoading && (
        <div className="flex flex-col gap-2 rounded border border-border bg-white p-4">
          <Skeleton height={16} className="w-1/2" />
          <Skeleton height={40} className="w-full" />
          <Skeleton height={40} className="w-full" />
        </div>
      )}

      {data && data.videos.length === 0 && (
        <EmptyState
          title="처리 중인 영상이 없습니다"
          message="배치 처리 대기 중이거나 모두 완료되었습니다."
        />
      )}

      {data && data.videos.length > 0 && (
        <ul
          data-testid="batch-video-list"
          className="flex flex-col gap-3"
        >
          {data.videos.map((v) => (
            <li
              key={v.videoId}
              className="rounded border border-border bg-white p-4"
            >
              <header className="mb-3 flex items-center justify-between">
                <div>
                  <p className="text-section-title text-primary">{v.cctvName}</p>
                  <p className="text-sub text-neutral">{v.vmsClipId}</p>
                </div>
                <span className="text-sub text-neutral">
                  현재 단계: {v.currentStage}
                </span>
              </header>
              <VideoStatusStepper stages={v.stages} />
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
