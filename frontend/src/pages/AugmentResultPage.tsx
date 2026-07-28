import { useEffect, useMemo, useRef, useState } from 'react';
import { useParams } from 'react-router-dom';

import { Button } from '@/components/common/Button';
import { ErrorState } from '@/components/common/ErrorState';
import { PageHeader } from '@/components/common/PageHeader';
import { Pagination } from '@/components/common/Pagination';
import { ProgressBar } from '@/components/common/ProgressBar';
import { Skeleton } from '@/components/common/Skeleton';
import { Spinner } from '@/components/common/Spinner';
import { StatusBadge } from '@/components/common/StatusBadge';
import { Tabs } from '@/components/common/Tabs';
import { KRDS_FOCUS } from '@/lib/focusRing';
import { augTypeLabel } from '@/features/augment/augTypeLabel';
import { DecisionCard } from '@/features/augment/components/DecisionCard';
import {
  useAcceptAugment,
  useRejectAugment,
} from '@/features/augment/hooks/useAugmentDecision';
import { useAugmentResult } from '@/features/augment/hooks/useAugmentResult';
import {
  AugmentType as AT,
  type AugmentResult as AugResult,
  type AugmentResultType,
} from '@/features/augment/types';
import { FrameGrid12 } from '@/features/deident/components/FrameGrid12';
import { SideBySideCompare } from '@/features/deident/components/SideBySideCompare';
import { useUiStore } from '@/stores/useUiStore';

const VISIBLE_INITIAL = 2;

// 프레임 쌍 페이지 크기 — BE 기본값(12)과 FrameGrid12 그리드 용량(12)에 맞춘다.
const FRAME_PAGE_SIZE = 12;

/** 페이징 전 전체 프레임 쌍 수 — 구 응답(totalFramePairs 없음)은 로드된 개수로 폴백. */
function totalPairsOf(r: AugResult): number {
  return r.totalFramePairs ?? r.framePairs.length;
}

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

  // 프레임 쌍 페이지(0-based) — BE 가 results[].framePairs 를 이 단위로 잘라 내려준다.
  const [framePage, setFramePage] = useState(0);
  const { data, isLoading, error } = useAugmentResult(validId ?? undefined, {
    page: framePage,
    size: FRAME_PAGE_SIZE,
  });
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

    const uniqueTypes = new Set<AugmentResultType>();
    const uniqueVideos = new Set<number>();
    let loadedPairs = 0;
    let augmentedPairs = 0;
    // 총 처리 이미지 = 페이징 전 전체 프레임 쌍 수 (현재 페이지 길이로 축소되면 안 된다).
    let totalPairs = 0;
    for (const r of data.results) {
      uniqueTypes.add(r.type);
      uniqueVideos.add(r.videoId);
      totalPairs += totalPairsOf(r);
      for (const p of r.framePairs) {
        loadedPairs += 1;
        if (p.augmentedUrl) augmentedPairs += 1;
      }
    }

    // 라벨 무결성 — 현재 페이지에 로드된 쌍 기준 증강 결과 이미지 산출 성공 비율.
    const labelIntegrity =
      loadedPairs > 0 ? Math.round((augmentedPairs / loadedPairs) * 100) : 0;

    // 상태는 BE 집계값을 그대로 사용한다. results.length 로 파생하지 않는다 —
    // 외부 SFR-07 연동 전이라 완료/실패 파생도 results 가 비어 있어, 파생하면 무조건 "처리 중"으로
    // 오표시된다. 구 응답 호환을 위해 status 미제공 시에만 PROCESSING 으로 폴백한다.
    const status: 'PROCESSING' | 'COMPLETED' | 'FAILED' = data.status ?? 'PROCESSING';

    return {
      types: Array.from(uniqueTypes),
      videoCount: uniqueVideos.size,
      totalImages: totalPairs,
      /** 결과 본문 유무 — 외부 연동 대기 안내(빈 결과) 판정용 */
      hasResults: data.results.length > 0,
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
                  {summary.types.map((t) => augTypeLabel(t)).join(' · ') || '-'}
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

          {/* COMPLETED — 결과 본문이 아직 없으면(외부 SFR-07 연동 전) 완료 안내만 표시 */}
          {summary.status === 'COMPLETED' && !summary.hasResults && (
            <div
              className="rounded border border-success/30 bg-success/10 p-4"
              role="status"
              data-testid="augment-result-completed-empty"
            >
              <p className="text-body font-semibold text-success">증강 처리 완료</p>
              <p className="text-sub text-success">
                파생 영상이 생성되어 작업 목록에서 라벨링·검수를 진행할 수 있습니다. 프레임별
                비교 결과는 외부 연동 이후 표시됩니다.
              </p>
            </div>
          )}

          {/* COMPLETED — 라벨 무결성 카드 (결과 본문이 있을 때만) */}
          {summary.status === 'COMPLETED' && summary.hasResults && (
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
                framePage={framePage}
                onFramePageChange={setFramePage}
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
  framePage,
  onFramePageChange,
}: {
  videoId: number;
  cctvName: string;
  results: AugResult[];
  framePage: number;
  onFramePageChange: (page: number) => void;
}) {
  const tabItems = useMemo(
    () =>
      results.map((r) => ({
        value: r.type,
        label: augTypeLabel(r.type),
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

  // 탭이 실제로 바뀌면 프레임 페이지를 첫 페이지로 되돌린다.
  // 유형마다 총 쌍 수가 다르므로(예 1080P=15, 480P=10) 페이지를 유지한 채 총량이 작은 탭으로
  // 옮기면 BE 가 빈 슬라이스를 내려 그리드가 0장이 되고, 페이저 표시 조건(총량 > 12)도 거짓이라
  // 되돌아갈 컨트롤조차 없어 갇힌다. 마운트 시점에는 리셋하지 않는다(다른 영상 섹션이 보고 있는
  // 페이지를 "더 보기" 확장만으로 되돌리지 않기 위해 직전 값과 비교한다).
  const prevTypeRef = useRef(activeType);
  useEffect(() => {
    if (prevTypeRef.current === activeType) return;
    prevTypeRef.current = activeType;
    onFramePageChange(0);
  }, [activeType, onFramePageChange]);

  const active = results.find((r) => r.type === activeType);

  return (
    <section
      aria-label={`영상 ${cctvName} 증강 결과`}
      data-testid={`augment-result-video-${videoId}`}
      className="rounded border border-border bg-white p-4"
    >
      <h2 className="mb-3 text-section-title text-primary">{cctvName}</h2>
      <Tabs items={tabItems} value={activeType} onChange={setActiveType}>
        {active && (
          <ResultPanel
            result={active}
            framePage={framePage}
            onFramePageChange={onFramePageChange}
          />
        )}
      </Tabs>
    </section>
  );
}

function ResultPanel({
  result,
  framePage,
  onFramePageChange,
}: {
  result: AugResult;
  framePage: number;
  onFramePageChange: (page: number) => void;
}) {
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

  const typeLabel = augTypeLabel(result.type);
  const totalPairs = totalPairsOf(result);
  // 12쌍을 넘으면 페이저로 나머지 쌍에 접근한다 (접근 불가 프레임 0).
  // `framePage > 0` 도 함께 조건에 둔다 — 탭 전환 리셋이 반영되기 전 한 렌더 동안(혹은 BE 총량이
  // 줄어든 경우) 페이저가 사라지면 첫 페이지로 돌아갈 컨트롤이 없어 빈 그리드에 갇힌다(안전망).
  const showPager = totalPairs > FRAME_PAGE_SIZE || framePage > 0;
  // 해상도 파생은 검수 대상이 아닌 내부 생성물 — 채택/거부 카드를 노출하지 않는다.
  // 구 응답(외부 위탁 증강)은 reviewable 필드가 없으므로 미지정은 검수 가능으로 본다.
  const reviewable = result.reviewable !== false;

  return (
    <div className="flex flex-col gap-3">
      <FrameGrid12
        frames={gridFrames}
        selectedSrcSn={selectedSrcSn}
        onSelect={setSelectedSrcSn}
        pairLabel={{ top: '원본', bottom: typeLabel }}
        authImages
      />
      {showPager && (
        <div data-testid="augment-frame-pager">
          <Pagination
            page={framePage}
            size={FRAME_PAGE_SIZE}
            totalElements={totalPairs}
            onPageChange={onFramePageChange}
          />
        </div>
      )}
      {selectedPair && (
        <SideBySideCompare
          leftImage={selectedPair.originalUrl}
          rightImage={selectedPair.augmentedUrl ?? ''}
          leftLabel="원본"
          rightLabel={typeLabel}
          rightMissingText={`${typeLabel} 이미지 없음`}
          authImages
        />
      )}
      {reviewable && (
        <DecisionCard
          status={result.decision}
          decidedAt={result.decidedAt}
          rejectReason={result.rejectReason}
          loading={accept.isPending || reject.isPending}
          onAccept={() => accept.mutate(result.id)}
          onReject={(reason) => reject.mutate({ id: result.id, reason })}
        />
      )}
    </div>
  );
}
