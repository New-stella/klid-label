import { useEffect, useMemo, useState } from 'react';
import { useParams } from 'react-router-dom';

import { Button } from '@/components/common/Button';
import { ErrorState } from '@/components/common/ErrorState';
import { PageHeader } from '@/components/common/PageHeader';
import { ProgressBar } from '@/components/common/ProgressBar';
import { Skeleton } from '@/components/common/Skeleton';
import { Spinner } from '@/components/common/Spinner';
import { StatusBadge } from '@/components/common/StatusBadge';
import { Tabs } from '@/components/common/Tabs';
import { KRDS_FOCUS } from '@/lib/focusRing';
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

// 현재 증강 유형은 3종(WINTER/NIGHT/RAIN). 과거 결과에 RESOLUTION 이 남아 있을 수 있어
// 레거시 라벨도 표시하되, 신규 요청 유형 union 에는 포함하지 않는다(SFR-06-03).
const TYPE_LABEL: Record<AugmentType | 'RESOLUTION', string> = {
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

  // 잡 레벨 요약 — API 응답에서 파생.
  const summary = useMemo(() => {
    if (!data) return null;

    const uniqueTypes = new Set<AugmentType>();
    const uniqueVideos = new Set<number>();
    let totalPairs = 0;
    let augmentedPairs = 0;
    for (const r of data.results) {
      uniqueTypes.add(r.type);
      uniqueVideos.add(r.videoId);
      for (const p of r.framePairs) {
        totalPairs += 1;
        if (p.augmentedUrl) augmentedPairs += 1;
      }
    }

    // 라벨 무결성 — 증강 결과 이미지 산출 성공 비율.
    const labelIntegrity =
      totalPairs > 0 ? Math.round((augmentedPairs / totalPairs) * 100) : 0;

    // 상태 파생: 결과 비어 있으면 PROCESSING, 무결성 0이면 FAILED 간주.
    let status: 'PROCESSING' | 'COMPLETED' | 'FAILED' = 'COMPLETED';
    if (data.results.length === 0) status = 'PROCESSING';
    else if (totalPairs > 0 && augmentedPairs === 0) status = 'FAILED';

    return {
      types: Array.from(uniqueTypes),
      videoCount: uniqueVideos.size,
      totalImages: totalPairs,
      labelIntegrity,
      progress: status === 'COMPLETED' ? 100 : status === 'PROCESSING' ? 50 : 0,
      status,
    };
  }, [data]);

  const integrityToneClass =
    summary && summary.labelIntegrity >= 95
      ? 'text-success'
      : summary && summary.labelIntegrity >= 85
        ? 'text-warning'
        : 'text-danger';

  return (
    <section className="flex flex-col gap-4" data-testid="augment-result-page">
      <PageHeader
        title="증강 결과 확인"
        breadcrumb={[
          { label: '데이터 증강', href: '/augment' },
          { label: `잡 #${validId}` },
        ]}
        actions={
          summary && <StatusBadge status={summary.status} />
        }
      />

      {error && <ErrorState title="증강 결과를 불러올 수 없습니다" />}
      {isLoading && (
        <div className="rounded border border-border bg-white p-4">
          <Skeleton height={120} />
        </div>
      )}

      {data && summary && (
        <>
          {/* 작업 요약 카드 */}
          <div
            className="rounded border border-border bg-white p-4"
            data-testid="augment-result-summary"
          >
            <h2 className="mb-3 text-section-title text-primary">작업 요약</h2>
            <dl className="grid grid-cols-2 gap-x-6 gap-y-3 text-body md:grid-cols-5">
              <div>
                <dt className="text-sub text-gray-500">작업 ID</dt>
                <dd className="font-mono text-sub text-gray-800">#{validId}</dd>
              </div>
              <div>
                <dt className="text-sub text-gray-500">증강 유형</dt>
                <dd className="font-medium text-gray-800">
                  {summary.types.map((t) => TYPE_LABEL[t]).join(' · ') || '-'}
                </dd>
              </div>
              <div>
                <dt className="text-sub text-gray-500">대상 영상</dt>
                <dd className="font-medium text-gray-800">{summary.videoCount}건</dd>
              </div>
              <div>
                <dt className="text-sub text-gray-500">총 처리 이미지</dt>
                <dd className="font-medium text-gray-800">
                  {summary.totalImages.toLocaleString()}장
                </dd>
              </div>
              <div>
                <dt className="text-sub text-gray-500">생성일</dt>
                <dd className="font-medium text-gray-800">-</dd>
              </div>
            </dl>

            {summary.status !== 'COMPLETED' && (
              <div className="mt-4 space-y-1.5">
                <div className="flex items-center justify-between text-sub text-gray-500">
                  <span>진행률</span>
                  <span className="font-medium tabular-nums">
                    {summary.progress}%
                  </span>
                </div>
                <ProgressBar
                  value={summary.progress}
                  tone={summary.status === 'FAILED' ? 'danger' : 'primary'}
                  size="md"
                />
              </div>
            )}
          </div>

          {/* PROCESSING 배너 */}
          {summary.status === 'PROCESSING' && (
            <div
              className="flex items-center gap-3 rounded border border-info/30 bg-info/10 p-4"
              role="status"
              data-testid="augment-result-processing"
            >
              <Spinner size="sm" />
              <div>
                <p className="text-body font-semibold text-info">
                  증강 처리 중입니다...
                </p>
                <p className="text-sub text-info">
                  잠시 후 자동으로 결과가 표시됩니다.
                </p>
              </div>
            </div>
          )}

          {/* FAILED 배너 */}
          {summary.status === 'FAILED' && (
            <div
              className="flex flex-wrap items-center justify-between gap-3 rounded border border-danger/30 bg-danger/10 p-4"
              role="alert"
              data-testid="augment-result-failed"
            >
              <div>
                <p className="text-body font-semibold text-danger">
                  증강 처리 실패
                </p>
                <p className="text-sub text-danger">
                  AI 서버 응답 오류 또는 리소스 부족으로 처리가 중단되었습니다.
                </p>
              </div>
              <Button variant="secondary" size="sm" onClick={() => location.reload()}>
                재시도
              </Button>
            </div>
          )}

          {/* COMPLETED — 라벨 무결성 카드 */}
          {summary.status === 'COMPLETED' && (
            <div
              className="flex flex-col items-center gap-2 rounded border border-border bg-white p-6"
              data-testid="augment-result-integrity"
            >
              <p className="text-body font-semibold text-gray-600">라벨 무결성</p>
              <span
                className={`text-5xl font-black tabular-nums ${integrityToneClass}`}
              >
                {summary.labelIntegrity}%
              </span>
              <p className="text-sub text-gray-400">
                {summary.labelIntegrity >= 95
                  ? '우수 — 라벨 정합성이 매우 높습니다'
                  : summary.labelIntegrity >= 85
                    ? '양호 — 일부 라벨을 검토해 주세요'
                    : '주의 — 라벨 검수가 필요합니다'}
              </p>
            </div>
          )}

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
              className={`self-start text-body text-accent underline ${KRDS_FOCUS}`}
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
