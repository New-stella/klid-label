import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';

import { Button } from '@/components/common/Button';
import { ConfirmDialog } from '@/components/common/ConfirmDialog';
import { ErrorState } from '@/components/common/ErrorState';
import { PageHeader } from '@/components/common/PageHeader';
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
 * SCR-REVIEW-002 검수 화면 (V1.x mock 시각 정합).
 *
 * UI/UX §4-9 정합:
 * - 헤더: 영상명·작업자·제출일 + StatusBadge + [승인][반려]
 * - 메인: 캔버스(읽기 전용 라벨 오버레이) + 전체 의견 textarea
 * - 사이드바: IssueSidebar (프레임별 카드 누적)
 * - **캔버스 좌표 마커 컴포넌트 미사용** (회귀 방지)
 *
 * 보안:
 * - reviewId path 파라미터는 number 변환 (NaN 가드).
 * - REVIEWER 권한은 라우터 RoleGuard에서 검증.
 * - 라벨 데이터 접근(IDOR)은 BE에서 본인 배정 검증.
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
    return <ErrorState title="잘못된 검수 ID" />;
  }

  if (isLoading) {
    return (
      <div className="flex h-full items-center justify-center py-10">
        <Spinner label="검수 로딩" />
      </div>
    );
  }

  if (error || !review) {
    return <ErrorState title="검수 정보를 불러올 수 없습니다" />;
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
    <div className="flex h-full flex-col gap-3" data-testid="review-page">
      <PageHeader
        title={`검수 — ${review.cctvName}`}
        description={`작업자: ${review.workerName} · 제출: ${submittedDate} · 라벨 ${review.labelCount.toLocaleString('ko-KR')}건`}
        breadcrumb={[
          { label: '검수 대기', href: '/review' },
          { label: review.cctvName },
        ]}
        actions={
          <div className="flex items-center gap-2">
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
        }
      />

      <div className="flex flex-1 gap-3 overflow-hidden">
        <main className="flex flex-1 flex-col gap-3 overflow-auto">
          {/* 라벨 캔버스 — 검수 화면에서는 읽기 전용 오버레이만.
              UI/UX §4-9 정합 — 캔버스 좌표 마커 컴포넌트는 절대 사용하지 않는다 (회귀 방지). */}
          <div
            className="relative flex aspect-video items-center justify-center overflow-hidden rounded-lg border border-gray-200 bg-gray-900"
            data-testid="review-canvas-readonly"
            aria-label="검수 캔버스 (읽기 전용)"
          >
            <div className="absolute left-3 top-3 inline-flex items-center gap-1.5 rounded-md bg-yellow-600/90 px-2 py-1 text-sub font-medium text-white">
              읽기 전용
            </div>
            <span className="text-body text-gray-300">
              라벨 오버레이 (읽기 전용 — 좌표 마커 미사용)
            </span>
          </div>

          <GeneralCommentTextarea
            value={generalComment}
            onChange={setGeneralComment}
            disabled={approving}
          />
        </main>
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
