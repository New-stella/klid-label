import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { ExternalLink } from 'lucide-react';

import { Button } from '@/components/common/Button';
import { Pagination } from '@/components/common/Pagination';
import { FrameGrid12 } from '@/features/deident/components/FrameGrid12';
import { SideBySideCompare } from '@/features/deident/components/SideBySideCompare';
import { extractBeMessage } from '@/lib/api/extractBeMessage';
import { KRDS_FOCUS } from '@/lib/focusRing';
import { useUiStore } from '@/stores/useUiStore';

import { augTypeLabel } from '../augTypeLabel';
import {
  useAcceptAugment,
  useRejectAugment,
  useRestoreAugment,
} from '../hooks/useAugmentDecision';
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
    // 사용자 노출 문구는 확정 용어 '반려'(사양 SCREEN-023). 훅·API 식별자는 계약이라 그대로다.
    onSuccess: () => pushToast({ variant: 'success', message: '반려 처리됨' }),
    onError: () => pushToast({ variant: 'error', message: '반려 처리 실패' }),
  });
  /**
   * 폐기(반려) 복구 — 실패 안내는 **BE 가 준 문구를 그대로** 쓴다.
   *
   * 실패는 코드도 사유도 여러 갈래다(BE `AugmentDiscardService` 실측):
   * - **404** `"복구할 폐기 이력이 없습니다."` — 되돌릴 결정 자체가 없다(표식도 REJECTED 검수 행도 없음)
   * - **404** `"증강 결과를 찾을 수 없습니다."` — 증강 행이 이미 사라졌다
   * - **409** `"복구할 검수 이력이 없습니다."` — 표식은 열려 있는데 스윕이 검수 행을 먼저 지운 레이스
   * - **409** `"유예 기간이 지나 이미 삭제된 파생영상입니다…"` — 실삭제 커밋과 경합
   *
   * 코드로도 메시지로도 **분기하지 않는다** — 필요한 반응이 전부 같고(서버 안내 노출 + 재동기화),
   * 메시지 문자열로 가르면 BE 문구가 바뀔 때 화면이 조용히 깨진다. 화면 정정은 훅의 무효화 →
   * 재조회가 담당한다.
   *
   * ⚠ 정상 형상에서는 이 실패들이 **레이스에서만** 난다 — 버튼 가시성은 BE 의
   * `restoreEligible`(복구 사전조건 그대로)이 정하므로 "누르면 반드시 실패하는 버튼" 은 없다.
   */
  const restore = useRestoreAugment({
    onSuccess: () => pushToast({ variant: 'success', message: '복구 처리됨' }),
    onError: (err) =>
      pushToast({
        variant: 'error',
        message: extractBeMessage(err, '복구에 실패했습니다'),
      }),
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
  // 해상도 파생은 검수 대상이 아닌 내부 생성물 — 채택/반려 카드를 노출하지 않는다.
  // 외부 위탁 항목은 결정 이후(채택/반려/취소)에도 그 사실을 계속 보여준다.
  const reviewable = result.reviewable !== false;
  const showDecision = !isResolution && (decision !== 'PENDING' || reviewable);
  /**
   * 이 결과로 만들어진 **파생 영상**으로 가는 링크의 목적지.
   *
   * 매핑이 없는 항목(그랜드퍼더링 · 생성 실패)은 오류가 아니라 **원래 없는 것**이라 링크 자체를
   * 그리지 않는다(죽은 링크를 만들지 않는다). BE 가 null 로 내려주는 필드이므로 값 형태도 함께
   * 확인한다.
   *
   * **실삭제분도 그리지 않는다** — BE 는 `newRawSn` 을 폐기 여부와 무관하게 싣는데, 실삭제는
   * `LS_DATA_RAW` 행 자체를 지우므로 그 링크는 404 다. 같은 화면이 바로 위에서 "유예 기간이 지나
   * 삭제되었습니다" 를 띄우면서 "생성된 영상 상세 보기" 를 함께 그리는 자기모순이 된다. 판정은
   * **BE 가 이미 말해준 사실**(`discard.purged` = DB 실삭제 커밋됨)을 그대로 쓴다 — 같은 이유로
   * BE 도 이 창에서 프레임 쌍을 비운다(죽은 이미지 링크 차단).
   */
  const derivativeRawSn =
    result.discard?.purged !== true &&
    typeof result.derivativeRawSn === 'number' &&
    Number.isInteger(result.derivativeRawSn) &&
    result.derivativeRawSn > 0
      ? result.derivativeRawSn
      : null;

  return (
    <div className="flex flex-col gap-3" data-testid={`augment-result-item-${result.id}`}>
      <AugmentProgressPanel augmentId={result.id} enabled={!isResolution} />
      <AugmentPromptSummary augmentId={result.id} prompt={result.prompt} />
      {derivativeRawSn !== null && (
        // 검수 흐름이 끊기지 않도록 새 탭으로 연다. `target="_blank"` 에는 탭 하이재킹 방어를
        // 반드시 붙인다(CWE-1022).
        <Link
          to={`/video/${derivativeRawSn}`}
          target="_blank"
          rel="noopener noreferrer"
          data-testid="augment-derivative-link"
          className={`inline-flex items-center gap-1 self-start text-body text-accent underline ${KRDS_FOCUS}`}
        >
          <ExternalLink className="h-4 w-4" aria-hidden="true" />
          생성된 영상 상세 보기 (새 창)
        </Link>
      )}
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
          // 복구 버튼 가시성은 BE 사전조건 판정을 그대로 전달한다(FE 재유도 금지 — DEV_FIX HIGH-①).
          restoreEligible={result.restoreEligible}
          // 연타 방어는 FE 단독 책임(서버측 중복 차단·속도 제한을 두지 않는 정책) — 진행 중인
          // 결정 요청이 하나라도 있으면 이 카드의 모든 액션을 잠근다.
          loading={accept.isPending || reject.isPending || restore.isPending}
          onAccept={() => accept.mutate(result.id)}
          onReject={(reason) => reject.mutate({ id: result.id, reason })}
          onRestore={(reason) => restore.mutate({ id: result.id, reason })}
        />
      )}
    </div>
  );
}
