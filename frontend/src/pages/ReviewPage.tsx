// SCR-REVIEW-002 검수 화면 — Phase 2 mock(ReviewEditor) 정합 3분할 레이아웃.
//
// 레이아웃 (Phase 2 — 골격 + 헤더 + 액션 버튼):
//   ┌─ ReviewHeader (h-16, dark)
//   ├─ Main: [Canvas placeholder (flex-1)] [aside (360px) placeholder]
//   └─ Footer: [timeline placeholder] + ReviewActionBar
//
// 내부 영역(캔버스/객체 트리/타임라인/메모)은 Phase 3·4·6 에서 채움.
//
// UI/UX §4-9 정합:
// - 캔버스는 읽기 전용 (좌표 마커 절대 미사용 — 회귀 방지)
// - 이슈는 텍스트 카드만으로 표현 (프레임 단위 누적)
//
// 보안:
// - reviewId path 파라미터 number 변환 (NaN 가드).
// - REVIEWER 권한은 라우터 RoleGuard에서 검증.
// - 라벨 데이터 접근(IDOR)은 BE에서 본인 배정 검증.

import { useCallback, useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';

import { ConfirmDialog } from '@/components/common/ConfirmDialog';
import { ErrorState } from '@/components/common/ErrorState';
import { Spinner } from '@/components/common/Spinner';
import { FrameTimeline } from '@/features/review/components/FrameTimeline';
import { LabelCanvas } from '@/features/review/components/LabelCanvas';
import { ObjectAttributesPanel } from '@/features/review/components/ObjectAttributesPanel';
import { ObjectListPanel } from '@/features/review/components/ObjectListPanel';
import { RejectModal } from '@/features/review/components/RejectModal';
import { ReviewActionBar } from '@/features/review/components/ReviewActionBar';
import { ReviewHeader } from '@/features/review/components/ReviewHeader';
import { ReviewMemoPanel } from '@/features/review/components/ReviewMemoPanel';
import { useReview } from '@/features/review/hooks/useReview';
import {
  useApproveReview,
  useStartReview,
} from '@/features/review/hooks/useReviewActions';
import { useReviewFrames } from '@/features/review/hooks/useReviewFrames';
import { useReviewIssues } from '@/features/review/hooks/useReviewIssues';
import {
  useReviewSelectionStore,
  type PendingIssue,
} from '@/features/review/store/useReviewSelectionStore';
import { useUiStore } from '@/stores/useUiStore';

/**
 * 검수 의견 + pending 이슈를 반려 사유 문자열로 합성한다.
 * 형식 (사용자 입력 reason 을 우선):
 *
 *   {사용자 입력}
 *
 *   [전체 의견]
 *   {reviewComment}
 *
 *   [이슈 N건]
 *   - {text} (#{labelId})
 *   - ...
 */
export function composeRejectReason(
  userReason: string,
  reviewComment: string,
  pendingIssues: PendingIssue[],
): string {
  const parts: string[] = [userReason.trim()];
  if (reviewComment.trim()) {
    parts.push(`[전체 의견]\n${reviewComment.trim()}`);
  }
  if (pendingIssues.length > 0) {
    const bullets = pendingIssues
      .map((p) => {
        const label = p.labelId != null ? ` (#${p.labelId})` : '';
        return `- ${p.text}${label}`;
      })
      .join('\n');
    parts.push(`[이슈 ${pendingIssues.length}건]\n${bullets}`);
  }
  return parts.filter((s) => s.length > 0).join('\n\n');
}

/**
 * SCR-REVIEW-002 검수 화면 (3분할 — Phase 2).
 */
export function ReviewPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const numericId = id ? Number(id) : NaN;
  const reviewId = Number.isFinite(numericId) ? numericId : undefined;

  const [rejectOpen, setRejectOpen] = useState(false);
  const [approveConfirmOpen, setApproveConfirmOpen] = useState(false);
  const [didStart, setDidStart] = useState(false);

  // Phase 5·6 — store 구독 (selector 패턴, rules/state-management.md).
  const currentFrameIdx = useReviewSelectionStore((s) => s.currentFrameIdx);
  const setCurrentFrameIdx = useReviewSelectionStore(
    (s) => s.setCurrentFrameIdx,
  );
  const clearSelection = useReviewSelectionStore((s) => s.clear);
  // Phase 6 — reject 시 사유 합성을 위해 현재 값 스냅샷.
  const reviewComment = useReviewSelectionStore((s) => s.reviewComment);
  const pendingIssues = useReviewSelectionStore((s) => s.pendingIssues);

  const pushToast = useUiStore((s) => s.pushToast);
  const { data: review, isLoading, error } = useReview(reviewId);
  const { data: frameList } = useReviewFrames(review?.videoId);
  const { data: issues } = useReviewIssues(reviewId);

  const { mutate: doStart } = useStartReview({
    onSuccess: () => setDidStart(true),
  });

  const { mutate: doApprove, isPending: approving } = useApproveReview({
    onSuccess: () => {
      pushToast({ variant: 'success', message: '승인 완료' });
      navigate('/review');
    },
    onError: () => pushToast({ variant: 'error', message: '승인 실패' }),
  });

  // 검수 화면 진입 시 자동으로 startReview 호출 (REVIEW_PENDING → REVIEWING)
  useEffect(() => {
    if (review && review.status === 'REVIEW_PENDING' && !didStart) {
      doStart(review.id);
    }
  }, [review, didStart, doStart]);

  // Phase 5 — frames 로드 완료 시 currentFrameIdx 가 범위 밖이면 0 으로 reset.
  const frames = frameList?.frames;
  useEffect(() => {
    if (!frames || frames.length === 0) return;
    if (currentFrameIdx < 0 || currentFrameIdx >= frames.length) {
      setCurrentFrameIdx(0);
    }
  }, [frames, currentFrameIdx, setCurrentFrameIdx]);

  // Phase 5 — 페이지 언마운트 시 store reset (다른 검수 진입 시 잔존 상태 방지).
  useEffect(() => {
    return () => {
      clearSelection();
      setCurrentFrameIdx(0);
    };
  }, [clearSelection, setCurrentFrameIdx]);

  const handleClose = useCallback(() => {
    navigate('/review');
  }, [navigate]);

  const handleApproveClick = useCallback(() => {
    setApproveConfirmOpen(true);
  }, []);

  const handleRejectClick = useCallback(() => {
    setRejectOpen(true);
  }, []);

  const handleApproveConfirm = useCallback(() => {
    if (!review) return;
    setApproveConfirmOpen(false);
    doApprove({ reviewId: review.id });
  }, [doApprove, review]);

  if (Number.isNaN(numericId)) {
    return (
      <div
        className="fixed inset-0 z-50 flex items-center justify-center bg-gray-900 text-white"
        data-testid="review-page"
      >
        <ErrorState title="잘못된 검수 ID" />
      </div>
    );
  }

  if (isLoading) {
    return (
      <div
        className="fixed inset-0 z-50 flex items-center justify-center bg-gray-900 text-white"
        data-testid="review-page"
      >
        <div
          className="flex flex-col items-center gap-3"
          data-testid="review-page-loading"
        >
          <Spinner label="검수 로딩" />
          <p className="text-sm text-gray-300">검수 정보 로드 중...</p>
        </div>
      </div>
    );
  }

  if (error || !review) {
    return (
      <div
        className="fixed inset-0 z-50 flex items-center justify-center bg-gray-900 text-white"
        data-testid="review-page"
      >
        <ErrorState title="검수 정보를 불러올 수 없습니다" />
      </div>
    );
  }

  const totalFrames = frameList?.totalFrames ?? 0;

  return (
    <div
      className="fixed inset-0 z-50 grid overflow-hidden bg-gray-900"
      data-testid="review-page"
      style={{
        gridTemplateColumns: '1fr 360px',
        gridTemplateRows: '64px 1fr auto',
      }}
    >
      {/* Header — col-span-2 */}
      <div style={{ gridColumn: '1 / span 2' }}>
        <ReviewHeader
          videoId={review.videoId}
          cctvName={review.cctvName}
          workerName={review.workerName}
          submittedAt={review.submittedAt}
          currentFrame={currentFrameIdx + 1}
          totalFrames={totalFrames}
          status={review.status}
          onClose={handleClose}
        />
      </div>

      {/* Main canvas — Phase 3: Konva 기반 LabelCanvas 마운트 */}
      <main
        className="relative flex items-center justify-center overflow-hidden bg-gray-900"
        data-testid="review-canvas-readonly"
        aria-label="검수 캔버스 (읽기 전용)"
      >
        <div className="pointer-events-none absolute left-3 top-3 z-10 inline-flex items-center gap-1.5 rounded-md bg-yellow-600/90 px-2 py-1 text-xs font-medium text-white">
          읽기 전용
        </div>
        <LabelCanvas frame={frameList?.frames?.[currentFrameIdx] ?? null} />
      </main>

      {/* Aside — 객체 목록 / 속성 패널 (Phase 4) + 메모 placeholder (Phase 6) */}
      <aside
        className="flex flex-col overflow-y-auto border-l border-gray-700 bg-gray-800 text-gray-300"
        data-testid="review-aside"
        aria-label="객체 목록 및 속성"
      >
        <section
          className="border-b border-gray-700 p-3"
          aria-label="객체 목록"
          data-testid="review-aside-object-list"
        >
          <h2 className="mb-2 text-xs font-semibold uppercase tracking-wide text-gray-400">
            객체 목록
          </h2>
          <ObjectListPanel labels={frameList?.frames?.[currentFrameIdx]?.labels ?? []} />
        </section>

        <section
          className="border-b border-gray-700 p-3"
          aria-label="속성"
          data-testid="review-aside-attributes"
        >
          <h2 className="mb-2 text-xs font-semibold uppercase tracking-wide text-gray-400">
            속성
          </h2>
          <ObjectAttributesPanel
            labels={frameList?.frames?.[currentFrameIdx]?.labels ?? []}
          />
        </section>

        <ReviewMemoPanel videoId={review.videoId} issues={issues ?? []} />
      </aside>

      {/* Footer — FrameTimeline + ActionBar (col-span-2) */}
      <div style={{ gridColumn: '1 / span 2' }} data-testid="review-timeline-placeholder">
        <FrameTimeline
          frames={frameList?.frames ?? []}
          currentFrameIdx={currentFrameIdx}
          onSelect={setCurrentFrameIdx}
        />
        <ReviewActionBar
          status={review.status}
          onApprove={handleApproveClick}
          onReject={handleRejectClick}
          isPending={approving}
        />
      </div>

      <RejectModal
        reviewId={review.id}
        open={rejectOpen}
        onClose={() => setRejectOpen(false)}
        onSuccess={() => navigate('/review')}
        composeReason={(userReason) =>
          composeRejectReason(userReason, reviewComment, pendingIssues)
        }
      />

      <ConfirmDialog
        open={approveConfirmOpen}
        title="승인 확정"
        description="이 검수를 승인 처리하시겠습니까? 작업은 완료(COMPLETED) 상태로 전이됩니다."
        confirmLabel="승인 확정"
        variant="primary"
        loading={approving}
        onConfirm={handleApproveConfirm}
        onCancel={() => setApproveConfirmOpen(false)}
      />
    </div>
  );
}
