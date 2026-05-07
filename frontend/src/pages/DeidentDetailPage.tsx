import { useEffect, useMemo, useState } from 'react';
import { useParams } from 'react-router-dom';

import { ErrorState } from '@/components/common/ErrorState';
import { PageHeader } from '@/components/common/PageHeader';
import { Skeleton } from '@/components/common/Skeleton';
import { StatusBadge } from '@/components/common/StatusBadge';
import { FrameGrid12 } from '@/features/deident/components/FrameGrid12';
import { ProcessHistoryList } from '@/features/deident/components/ProcessHistoryList';
import { SideBySideCompare } from '@/features/deident/components/SideBySideCompare';
import { useDeidentDetail } from '@/features/deident/hooks/useDeidentDetail';
import type { DeidentStatus } from '@/features/deident/types';

/**
 * SCR-DEIDENT-002 비식별화 영상 단위 비교.
 *
 * 처리 정보 5컬럼 + FrameGrid12 + SideBySideCompare(50:50) + 처리 이력.
 * 보안: videoId number 검증. 이미지 URL은 BE 응답값만 사용.
 */
export function DeidentDetailPage() {
  const { videoId } = useParams<{ videoId: string }>();
  const numericId = Number.parseInt(videoId ?? '', 10);
  const validId = Number.isFinite(numericId) && numericId > 0 ? numericId : null;
  const { data, isLoading, error } = useDeidentDetail(validId ?? undefined);
  const [selectedSrcSn, setSelectedSrcSn] = useState<number | null>(null);

  // 데이터 로드 후 첫 프레임 자동 선택
  useEffect(() => {
    if (data && data.framePairs.length > 0 && selectedSrcSn === null) {
      setSelectedSrcSn(data.framePairs[0].srcSn);
    }
  }, [data, selectedSrcSn]);

  const selectedPair = useMemo(() => {
    if (!data || selectedSrcSn === null) return null;
    return data.framePairs.find((p) => p.srcSn === selectedSrcSn) ?? null;
  }, [data, selectedSrcSn]);

  if (validId === null) {
    return <ErrorState title="잘못된 영상 ID" message="유효한 영상 ID가 필요합니다." />;
  }

  return (
    <section className="flex flex-col gap-4">
      <PageHeader
        title="비식별화 영상 비교"
        breadcrumb={[
          { label: '비식별화', href: '/deident' },
          { label: `#${validId}` },
        ]}
      />

      {isLoading && (
        <div className="rounded border border-border bg-white p-4">
          <Skeleton height={20} className="w-1/3" />
          <Skeleton height={120} className="mt-3 w-full" />
        </div>
      )}

      {error && <ErrorState title="비식별 정보를 불러올 수 없습니다" />}

      {data && (
        <>
          <div
            data-testid="deident-info"
            className="grid grid-cols-2 gap-3 rounded border border-border bg-white p-4 sm:grid-cols-5"
          >
            <Field label="CCTV명">{data.cctvName}</Field>
            <Field label="Clip ID">{data.vmsClipId}</Field>
            <Field label="개인정보 유형">{data.prvcType}</Field>
            <Field label="상태">
              <StatusBadge status={mapStatus(data.status)} />
            </Field>
            <Field label="처리/실패/총">
              {data.processedFrames.toLocaleString('ko-KR')} /{' '}
              {data.failedFrames.toLocaleString('ko-KR')} /{' '}
              {data.totalFrames.toLocaleString('ko-KR')}
            </Field>
          </div>

          <section
            aria-label="12 프레임 비교"
            className="rounded border border-border bg-white p-4"
          >
            <h3 className="mb-3 text-section-title text-primary">프레임 페어 (최대 12)</h3>
            <FrameGrid12
              frames={data.framePairs}
              selectedSrcSn={selectedSrcSn}
              onSelect={setSelectedSrcSn}
            />
          </section>

          {selectedPair && (
            <section
              aria-label="좌우 비교 뷰"
              className="rounded border border-border bg-white p-4"
            >
              <h3 className="mb-3 text-section-title text-primary">
                선택 프레임 비교 (#{selectedPair.frameNo ?? selectedPair.srcSn})
              </h3>
              <SideBySideCompare
                leftImage={selectedPair.originalUrl}
                rightImage={selectedPair.processedUrl ?? ''}
                leftLabel="원본"
                rightLabel="비식별"
                rightMissingText="비식별 이미지 없음"
              />
            </section>
          )}

          <ProcessHistoryList items={data.history} />
        </>
      )}
    </section>
  );
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div>
      <dt className="text-sub text-neutral">{label}</dt>
      <dd className="text-body text-primary">{children}</dd>
    </div>
  );
}

function mapStatus(s: DeidentStatus): 'PENDING' | 'IN_PROGRESS' | 'COMPLETED' | 'BATCH_FAILED' {
  switch (s) {
    case 'COMPLETED':
      return 'COMPLETED';
    case 'IN_PROGRESS':
      return 'IN_PROGRESS';
    case 'FAILED':
      return 'BATCH_FAILED';
    case 'PENDING':
    default:
      return 'PENDING';
  }
}
