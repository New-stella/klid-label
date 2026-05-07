import { useEffect, useMemo, useState } from 'react';
import { useParams } from 'react-router-dom';

import { ErrorState } from '@/components/common/ErrorState';
import { PageHeader } from '@/components/common/PageHeader';
import { Skeleton } from '@/components/common/Skeleton';
import { Tabs } from '@/components/common/Tabs';
import { DecisionCard } from '@/features/augment/components/DecisionCard';
import {
  useAcceptAugment,
  useRejectAugment,
} from '@/features/augment/hooks/useAugmentDecision';
import { useAugmentResult } from '@/features/augment/hooks/useAugmentResult';
import {
  AugmentType as AT,
  type AugmentResult as AugResult,
  type AugmentType,
} from '@/features/augment/types';
import { FrameGrid12 } from '@/features/deident/components/FrameGrid12';
import { SideBySideCompare } from '@/features/deident/components/SideBySideCompare';
import { useUiStore } from '@/stores/useUiStore';

const TYPE_LABEL: Record<AugmentType, string> = {
  WINTER: '겨울',
  NIGHT: '야간',
  RAIN: '비',
  RESOLUTION: '해상도',
};

const VISIBLE_INITIAL = 2;

/**
 * SCR-AUG-002 증강 결과 (`/augment/result/:jobId`).
 *
 * UI/UX §4-12:
 * - 영상별 섹션 (2건 초과 시 더 보기)
 * - 유형 탭 + FrameGrid12(Phase 7 재사용) + 좌우 비교 + DecisionCard
 *
 * 보안: jobId는 number 타입 검증. URL 이미지는 BE 응답값만 사용.
 */
export function AugmentResultPage() {
  const { jobId } = useParams<{ jobId: string }>();
  const numericId = Number.parseInt(jobId ?? '', 10);
  const validId = Number.isFinite(numericId) && numericId > 0 ? numericId : null;

  const { data, isLoading, error } = useAugmentResult(validId ?? undefined);
  const [showAll, setShowAll] = useState(false);

  if (validId === null) {
    return <ErrorState title="잘못된 잡 ID" message="유효한 잡 ID가 필요합니다." />;
  }

  // 영상별 그룹핑
  const groupedByVideo = useMemo(() => {
    const map = new Map<number, { cctvName: string; results: AugResult[] }>();
    if (!data) return map;
    for (const r of data.results) {
      const cur = map.get(r.videoId);
      if (cur) {
        cur.results.push(r);
      } else {
        map.set(r.videoId, { cctvName: r.cctvName, results: [r] });
      }
    }
    return map;
  }, [data]);

  return (
    <section className="flex flex-col gap-4" data-testid="augment-result-page">
      <PageHeader
        title="증강 결과 확인"
        breadcrumb={[
          { label: '데이터 증강', href: '/augment' },
          { label: `잡 #${validId}` },
        ]}
      />

      {error && <ErrorState title="증강 결과를 불러올 수 없습니다" />}
      {isLoading && (
        <div className="rounded border border-border bg-white p-4">
          <Skeleton height={120} />
        </div>
      )}

      {data && (
        <>
          {Array.from(groupedByVideo.entries())
            .slice(0, showAll ? undefined : VISIBLE_INITIAL)
            .map(([videoId, group]) => (
              <VideoSection
                key={videoId}
                videoId={videoId}
                cctvName={group.cctvName}
                results={group.results}
              />
            ))}

          {groupedByVideo.size > VISIBLE_INITIAL && !showAll && (
            <button
              type="button"
              onClick={() => setShowAll(true)}
              data-testid="augment-result-show-more"
              className="self-start text-body text-accent underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent"
            >
              더 보기 ({groupedByVideo.size - VISIBLE_INITIAL}건)
            </button>
          )}
        </>
      )}
    </section>
  );
}

function VideoSection({
  videoId,
  cctvName,
  results,
}: {
  videoId: number;
  cctvName: string;
  results: AugResult[];
}) {
  const tabItems = useMemo(
    () =>
      results.map((r) => ({
        value: r.type,
        label: TYPE_LABEL[r.type],
      })),
    [results],
  );
  const [activeType, setActiveType] = useState<string>(
    results[0]?.type ?? AT.WINTER,
  );

  // 결과 변경 시 첫 탭으로
  useEffect(() => {
    if (!results.find((r) => r.type === activeType)) {
      setActiveType(results[0]?.type ?? AT.WINTER);
    }
  }, [results, activeType]);

  const active = results.find((r) => r.type === activeType);

  return (
    <section
      aria-label={`영상 ${cctvName} 증강 결과`}
      data-testid={`augment-result-video-${videoId}`}
      className="rounded border border-border bg-white p-4"
    >
      <h2 className="mb-3 text-section-title text-primary">{cctvName}</h2>
      <Tabs items={tabItems} value={activeType} onChange={setActiveType}>
        {active && <ResultPanel result={active} />}
      </Tabs>
    </section>
  );
}

function ResultPanel({ result }: { result: AugResult }) {
  const [selectedSrcSn, setSelectedSrcSn] = useState<number | null>(
    result.framePairs[0]?.srcSn ?? null,
  );
  const pushToast = useUiStore((s) => s.pushToast);

  useEffect(() => {
    setSelectedSrcSn(result.framePairs[0]?.srcSn ?? null);
  }, [result.id, result.framePairs]);

  const accept = useAcceptAugment({
    onSuccess: () => pushToast({ variant: 'success', message: '채택 처리됨' }),
    onError: () => pushToast({ variant: 'error', message: '채택 처리 실패' }),
  });
  const reject = useRejectAugment({
    onSuccess: () => pushToast({ variant: 'success', message: '거부 처리됨' }),
    onError: () => pushToast({ variant: 'error', message: '거부 처리 실패' }),
  });

  const selectedPair =
    selectedSrcSn !== null
      ? result.framePairs.find((p) => p.srcSn === selectedSrcSn)
      : null;

  // FrameGrid12에 전달 (originalUrl/processedUrl 매핑)
  const gridFrames = result.framePairs.map((p) => ({
    srcSn: p.srcSn,
    frameNo: p.frameNo,
    originalUrl: p.originalUrl,
    processedUrl: p.augmentedUrl,
  }));

  return (
    <div className="flex flex-col gap-3">
      <FrameGrid12
        frames={gridFrames}
        selectedSrcSn={selectedSrcSn}
        onSelect={setSelectedSrcSn}
        pairLabel={{ top: '원본', bottom: TYPE_LABEL[result.type] }}
      />
      {selectedPair && (
        <SideBySideCompare
          leftImage={selectedPair.originalUrl}
          rightImage={selectedPair.augmentedUrl ?? ''}
          leftLabel="원본"
          rightLabel={TYPE_LABEL[result.type]}
          rightMissingText={`${TYPE_LABEL[result.type]} 이미지 없음`}
        />
      )}
      <DecisionCard
        status={result.decision}
        decidedAt={result.decidedAt}
        rejectReason={result.rejectReason}
        loading={accept.isPending || reject.isPending}
        onAccept={() => accept.mutate(result.id)}
        onReject={(reason) => reject.mutate({ id: result.id, reason })}
      />
    </div>
  );
}
