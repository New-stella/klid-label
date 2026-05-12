// SCR-REVIEW-002 검수 화면 — mock(ReviewEditor) 정합 풀스크린 다크 캔버스 에디터.
//
// 레이아웃 (LabelingPage와 동일 패턴):
//   ┌─ Top bar (h-14, bg-gray-800): [X 닫기] [영상명/작업자] [상태/이슈수] [승인][반려]
//   ├─ flex-1: [Canvas flex-1 (읽기 전용)] [RightPanel w-80 — 객체목록 + 이슈 사이드바]
//   └─ Bottom (선택): 추후 FrameStrip — 현재는 단일 프레임이라 미노출
//
// UI/UX §4-9 정합:
// - 캔버스는 읽기 전용 (좌표 마커 절대 미사용 — 회귀 방지)
// - 이슈는 텍스트 카드만으로 표현 (프레임 단위 누적)
//
// 보안:
// - reviewId path 파라미터 number 변환 (NaN 가드).
// - REVIEWER 권한은 라우터 RoleGuard에서 검증.
// - 라벨 데이터 접근(IDOR)은 BE에서 본인 배정 검증.

import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { X } from 'lucide-react';

import { Button } from '@/components/common/Button';
import { ConfirmDialog } from '@/components/common/ConfirmDialog';
import { ErrorState } from '@/components/common/ErrorState';
import { Spinner } from '@/components/common/Spinner';
import { StatusBadge } from '@/components/common/StatusBadge';
import { GeneralCommentTextarea } from '@/features/review/components/GeneralCommentTextarea';
import { IssueSidebar } from '@/features/review/components/IssueSidebar';
import { RejectModal } from '@/features/review/components/RejectModal';
import { useReview } from '@/features/review/hooks/useReview';
import {
  useApproveReview,
  useStartReview,
} from '@/features/review/hooks/useReviewActions';
import { useUiStore } from '@/stores/useUiStore';

/**
 * SCR-REVIEW-002 검수 화면 (풀스크린 다크 — mock 정합).
 */
export function ReviewPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const numericId = id ? Number(id) : NaN;
  const reviewId = Number.isFinite(numericId) ? numericId : undefined;

  const [generalComment, setGeneralComment] = useState('');
  const [rejectOpen, setRejectOpen] = useState(false);
  const [approveConfirmOpen, setApproveConfirmOpen] = useState(false);
  const [didStart, setDidStart] = useState(false);

  const pushToast = useUiStore((s) => s.pushToast);
  const { data: review, isLoading, error } = useReview(reviewId);

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
        <div className="flex flex-col items-center gap-3">
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

  const handleApproveConfirm = () => {
    setApproveConfirmOpen(false);
    doApprove({
      reviewId: review.id,
      body: generalComment ? { generalComment } : undefined,
    });
  };

  const submittedDate = new Date(review.submittedAt).toLocaleString('ko-KR');

  return (
    <div
      className="fixed inset-0 z-50 flex flex-col overflow-hidden bg-gray-900"
      data-testid="review-page"
    >
      {/* ── Top bar ── */}
      <header
        className="flex shrink-0 items-center gap-3 border-b border-gray-700 bg-gray-800 px-4"
        style={{ height: 56 }}
      >
        <button
          type="button"
          onClick={() => navigate('/review')}
          className="rounded p-1.5 text-gray-400 transition-colors hover:bg-gray-700 hover:text-white"
          aria-label="나가기"
        >
          <X size={18} />
        </button>
        <div className="min-w-0 flex-1">
          <p className="truncate text-sm font-semibold text-white">
            {review.cctvName} 영상
          </p>
          <p className="truncate text-xs text-gray-400">
            작업자: {review.workerName} · 제출: {submittedDate} · 라벨{' '}
            {review.labelCount.toLocaleString('ko-KR')}건
          </p>
        </div>
        <div className="flex shrink-0 items-center gap-2">
          <StatusBadge status={review.status} />
          <Button
            variant="primary"
            onClick={() => setApproveConfirmOpen(true)}
            disabled={approving || review.status === 'COMPLETED'}
            aria-label="승인"
          >
            승인
          </Button>
          <Button
            variant="danger"
            onClick={() => setRejectOpen(true)}
            disabled={approving || review.status === 'REJECTED'}
            aria-label="반려"
          >
            반려
          </Button>
        </div>
      </header>

      {/* ── Main content ── */}
      <div className="flex flex-1 overflow-hidden">
        {/* Canvas area (읽기 전용) */}
        <main className="flex flex-1 flex-col overflow-hidden">
          <div
            className="relative flex flex-1 items-center justify-center overflow-hidden bg-gray-900"
            data-testid="review-canvas-readonly"
            aria-label="검수 캔버스 (읽기 전용)"
          >
            <div className="absolute left-3 top-3 inline-flex items-center gap-1.5 rounded-md bg-yellow-600/90 px-2 py-1 text-xs font-medium text-white">
              읽기 전용
            </div>
            <span className="text-sm text-gray-300">
              라벨 오버레이 (읽기 전용 — 좌표 마커 미사용)
            </span>
          </div>

          {/* 전체 의견 — 캔버스 하단 */}
          <div className="shrink-0 border-t border-gray-700 bg-gray-800 px-4 py-3">
            <GeneralCommentTextarea
              value={generalComment}
              onChange={setGeneralComment}
              disabled={approving}
            />
          </div>
        </main>

        {/* Right panel — IssueSidebar */}
        <IssueSidebar reviewId={review.id} />
      </div>

      <RejectModal
        reviewId={review.id}
        open={rejectOpen}
        onClose={() => setRejectOpen(false)}
        onSuccess={() => navigate('/review')}
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
