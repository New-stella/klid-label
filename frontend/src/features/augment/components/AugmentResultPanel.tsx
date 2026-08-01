import { useEffect, useState } from 'react';

import { Button } from '@/components/common/Button';
import { Pagination } from '@/components/common/Pagination';
import { FrameGrid12 } from '@/features/deident/components/FrameGrid12';
import { SideBySideCompare } from '@/features/deident/components/SideBySideCompare';
import { useUiStore } from '@/stores/useUiStore';

import { augTypeLabel } from '../augTypeLabel';
import { useAcceptAugment, useRejectAugment } from '../hooks/useAugmentDecision';
import {
  isResolutionDerivativeType,
  type AugmentResult,
} from '../types';
import { emptyPairsMessage, normalizeDecision, totalPairsOf } from '../resultView';

import { AugmentProgressPanel } from './AugmentProgressPanel';
import { AugmentPromptSummary } from './AugmentPromptSummary';
import { DecisionCard } from './DecisionCard';

/** 프레임 쌍 페이지 크기 — BE 기본값(12)과 FrameGrid12 그리드 용량(12)에 맞춘다. */
export const FRAME_PAGE_SIZE = 12;

export interface AugmentResultPanelProps {
  result: AugmentResult;
  framePage: number;
  onFramePageChange: (page: number) => void;
}

/**
 * 결과 항목 1건 패널 — 진행 상태 · 생성 조건 · 프레임 비교 · 활용 결정.
 *
 * 같은 종류를 여러 번 요청할 수 있으므로 이 패널은 **항목(id) 단위**로 렌더된다.
 *
 * <h3>프레임 쌍이 0장인 "이유" 는 개수가 아니라 `resultState` 가 말한다</h3>
 * 외부 위탁·해상도 파생 **둘 다** 프레임 쌍을 채우므로, 0장은 더 이상 "외부 연동 전" 을 뜻하지
 * 않는다. 생성 중 · 반입 중 · 신고 보류 · 영구 실패 · 취소 · 실삭제가 전부 0장으로 관측되며 대응이
 * 전혀 다르다(기다리면 되는가 / 기다려도 소용없는가). 그 구분은 BE 의 `resultState` 축이 정본이고
 * 화면은 그 값을 문구로만 옮긴다(`emptyPairsMessage`).
 */
export function AugmentResultPanel({
  result,
  framePage,
  onFramePageChange,
}: AugmentResultPanelProps) {
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
  const showFramePager = totalPairs > FRAME_PAGE_SIZE || framePage > 0;
  /**
   * 쌍이 실재하는데 이 페이지에만 없는 상태인가 — **안전망이 필요한 바로 그 순간**.
   *
   * 구 구현은 위 안전망(`framePage > 0`)을 `gridFrames.length > 0` 분기 <안>에 두어, 정작 그리드가
   * 빈 순간에는 페이저가 없고 대신 "프레임별 비교 결과는 외부 연동 이후 표시됩니다" 가 떴다 —
   * 쌍이 실재하는 해상도 파생 항목에 대해 **사실과 다른 안내**이고, 첫 페이지로 돌아갈 컨트롤도
   * 없다(탭 전환 직후 stale 프레임 페이지 창에서 재현).
   */
  const emptyFramePage = gridFrames.length === 0 && (totalPairs > 0 || framePage > 0);
  const isResolution = isResolutionDerivativeType(result.type);
  const decision = normalizeDecision(result.decision);
  // 해상도 파생은 검수 대상이 아닌 내부 생성물 — 채택/거부 카드를 노출하지 않는다.
  // 외부 위탁 항목은 결정 이후(채택/거부/취소)에도 그 사실을 계속 보여준다.
  const reviewable = result.reviewable !== false;
  const showDecision = !isResolution && (decision !== 'PENDING' || reviewable);

  return (
    <div className="flex flex-col gap-3" data-testid={`augment-result-item-${result.id}`}>
      <AugmentProgressPanel augmentId={result.id} enabled={!isResolution} />
      <AugmentPromptSummary augmentId={result.id} prompt={result.prompt} />
      {gridFrames.length > 0 ? (
        <>
          <FrameGrid12
            frames={gridFrames}
            selectedSrcSn={selectedSrcSn}
            onSelect={setSelectedSrcSn}
            pairLabel={{ top: '원본', bottom: typeLabel }}
            authImages
          />
          {showFramePager && (
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
        </>
      ) : emptyFramePage ? (
        <div
          data-testid={`augment-result-empty-page-${result.id}`}
          className="flex flex-col items-start gap-2 rounded border border-dashed border-border p-4 text-sub text-gray-500"
        >
          <p>
            이 페이지에는 표시할 프레임 쌍이 없습니다 (전체{' '}
            {totalPairs.toLocaleString('ko-KR')}쌍).
          </p>
          <div className="flex flex-wrap items-center gap-2">
            <Button
              variant="outline"
              size="sm"
              data-testid={`augment-frame-first-page-${result.id}`}
              onClick={() => onFramePageChange(0)}
              disabled={framePage === 0}
            >
              첫 페이지로
            </Button>
          </div>
          {totalPairs > FRAME_PAGE_SIZE && (
            <div data-testid="augment-frame-pager" className="w-full">
              <Pagination
                page={framePage}
                size={FRAME_PAGE_SIZE}
                totalElements={totalPairs}
                onPageChange={onFramePageChange}
              />
            </div>
          )}
        </div>
      ) : (
        <p
          data-testid={`augment-result-no-pairs-${result.id}`}
          className="rounded border border-dashed border-border p-4 text-sub text-gray-500"
        >
          {emptyPairsMessage(result.resultState)}
        </p>
      )}
      {showDecision && (
        <DecisionCard
          status={decision}
          decidedAt={result.decidedAt}
          rejectReason={result.rejectReason}
          discard={result.discard}
          loading={accept.isPending || reject.isPending}
          onAccept={() => accept.mutate(result.id)}
          onReject={(reason) => reject.mutate({ id: result.id, reason })}
        />
      )}
    </div>
  );
}
