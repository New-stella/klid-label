import { ErrorState } from '@/components/common/ErrorState';
import { KpiCard } from '@/components/common/KpiCard';
import { PageHeader } from '@/components/common/PageHeader';
import { Skeleton } from '@/components/common/Skeleton';
import { EmptyState } from '@/components/common/EmptyState';
import { useBatchStatus } from '@/features/video/hooks/useBatchStatus';
import type { BatchStageProgress } from '@/features/video/types';

const STAGE_LABELS: Record<string, string> = {
  PENDING: '대기',
  FRAME_EXTRACT: '프레임 추출',
  DEIDENTIFY: '비식별화',
  YOLO: 'YOLO',
  SAM2: 'SAM2',
  VLM_VERIFY: 'VLM 검증',
  COMPLETED: '완료',
  FAILED: '실패',
};

const STAGE_ORDER = ['PENDING', 'FRAME_EXTRACT', 'DEIDENTIFY', 'YOLO', 'SAM2', 'VLM_VERIFY', 'COMPLETED'];

function stageColor(stage: string) {
  if (stage === 'COMPLETED') return 'text-success bg-success/10';
  if (stage === 'FAILED') return 'text-error bg-error/10';
  if (stage === 'PENDING') return 'text-neutral bg-neutral/10';
  return 'text-primary bg-primary/10';
}

function BatchItemRow({ item }: { item: BatchStageProgress }) {
  const stageIdx = STAGE_ORDER.indexOf(item.stage);
  const progress = item.stage === 'COMPLETED'
    ? 100
    : item.stage === 'FAILED' || item.stage === 'PENDING'
      ? 0
      : Math.round(((stageIdx) / (STAGE_ORDER.length - 2)) * 100);

  return (
    <li className="rounded border border-border bg-white p-4">
      <div className="mb-3 flex items-center justify-between">
        <div>
          <p className="text-section-title text-primary">원본 #{item.rawSn}</p>
          <p className="text-sub text-neutral">
            시작: {item.startedAt ? new Date(item.startedAt).toLocaleString('ko-KR') : '-'}
          </p>
        </div>
        <span className={`rounded-full px-3 py-1 text-sub font-medium ${stageColor(item.stage)}`}>
          {STAGE_LABELS[item.stage] ?? item.stage}
        </span>
      </div>

      {/* 진행 바 */}
      <div className="mb-2 h-2 w-full overflow-hidden rounded-full bg-neutral/20">
        <div
          className={`h-full rounded-full transition-all ${item.stage === 'FAILED' ? 'bg-error' : 'bg-primary'}`}
          style={{ width: `${progress}%` }}
        />
      </div>
      <p className="text-sub text-neutral">{progress}%</p>

      {item.errorMessage && (
        <p className="mt-2 text-sub text-error">오류: {item.errorMessage}</p>
      )}
      {item.retryCount > 0 && (
        <p className="text-sub text-neutral">재시도: {item.retryCount}회</p>
      )}
    </li>
  );
}

/**
 * SCR-VIDEO-002 처리 현황.
 * 5초 폴링 + 단계 표시 + 진행률 바.
 * BE 응답: GET /v1/batch/status → { items: BatchStageProgress[] }
 */
export function VideoStatusPage() {
  const { data, isLoading, error } = useBatchStatus();
  const items = data?.items ?? [];

  const totalProcessing = items.filter(
    (i) => !['COMPLETED', 'FAILED', 'PENDING'].includes(i.stage),
  ).length;
  const totalCompleted = items.filter((i) => i.stage === 'COMPLETED').length;
  const totalFailed = items.filter((i) => i.stage === 'FAILED').length;

  return (
    <section className="flex flex-col gap-4">
      <PageHeader
        title="처리 현황"
        description="배치 파이프라인의 실시간 처리 현황 (5초마다 자동 갱신)"
      />

      <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
        <KpiCard label="처리 중" value={totalProcessing} unit="건" />
        <KpiCard label="완료" value={totalCompleted} unit="건" />
        <KpiCard label="실패" value={totalFailed} unit="건" />
      </div>

      {error && <ErrorState title="배치 현황을 불러올 수 없습니다" />}

      {isLoading && (
        <div className="flex flex-col gap-2 rounded border border-border bg-white p-4">
          <Skeleton height={16} className="w-1/2" />
          <Skeleton height={40} className="w-full" />
          <Skeleton height={40} className="w-full" />
        </div>
      )}

      {data && items.length === 0 && (
        <EmptyState
          title="처리 중인 영상이 없습니다"
          message="배치 처리 대기 중이거나 모두 완료되었습니다."
        />
      )}

      {data && items.length > 0 && (
        <ul data-testid="batch-video-list" className="flex flex-col gap-3">
          {items.map((item) => (
            <BatchItemRow key={item.rawSn} item={item} />
          ))}
        </ul>
      )}
    </section>
  );
}
