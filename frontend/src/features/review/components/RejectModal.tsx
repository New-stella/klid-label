import { zodResolver } from '@hookform/resolvers/zod';
import { useForm } from 'react-hook-form';
import { z } from 'zod';

import { Button } from '@/components/common/Button';
import { Modal } from '@/components/common/Modal';
import { Textarea } from '@/components/common/Textarea';
import { useUiStore } from '@/stores/useUiStore';

import { useRejectReview } from '../hooks/useReviewActions';

/**
 * 반려 사유 스키마 — UI/UX §4-9 정합 (텍스트만, 1~1000자 필수).
 */
const rejectSchema = z.object({
  reason: z.string().min(1, '반려 사유를 입력하세요').max(1000, '최대 1000자'),
});
type RejectForm = z.infer<typeof rejectSchema>;

export interface RejectModalProps {
  reviewId: number;
  open: boolean;
  onClose(): void;
  onSuccess?(): void;
  /**
   * Phase 6 — 사용자가 입력한 사유에 추가 컨텍스트를 합성하기 위한 콜백.
   * 미지정 시 사용자 입력만 그대로 전송 (하위 호환).
   * 예: reviewComment + pendingIssues 통합.
   */
  composeReason?(userReason: string): string;
}

/**
 * SCR-REVIEW-003 반려 처리 모달.
 *
 * UI/UX §4-9 정합 — 반려 사유 텍스트 외의 입력 폼은 절대 추가하지 않는다 (회귀 방지).
 *
 * 보안:
 * - reason은 zod로 길이 검증 (min 1, max 1000) — 빈 사유 제출 차단.
 * - reviewId는 number 타입 — IDOR/Injection 방어는 BE 책임.
 */
export function RejectModal({
  reviewId,
  open,
  onClose,
  onSuccess,
  composeReason,
}: RejectModalProps) {
  const pushToast = useUiStore((s) => s.pushToast);

  const {
    register,
    handleSubmit,
    reset,
    formState: { errors, isValid },
  } = useForm<RejectForm>({
    resolver: zodResolver(rejectSchema),
    mode: 'onChange',
    defaultValues: { reason: '' },
  });

  const { mutate, isPending } = useRejectReview({
    onSuccess: () => {
      pushToast({ variant: 'success', message: '반려 처리됨' });
      reset({ reason: '' });
      onClose();
      onSuccess?.();
    },
    onError: () => {
      pushToast({ variant: 'error', message: '반려 처리 실패' });
    },
  });

  const onSubmit = (values: RejectForm) => {
    const finalReason = composeReason
      ? composeReason(values.reason)
      : values.reason;
    mutate({ reviewId, body: { reason: finalReason } });
  };

  return (
    <Modal
      open={open}
      onClose={onClose}
      title="반려 처리"
      description="반려 사유를 입력해 작업자에게 다시 전달합니다."
      size="md"
    >
      <form onSubmit={handleSubmit(onSubmit)} className="flex flex-col gap-3" noValidate>
        <Textarea
          label="반려 사유"
          rows={5}
          error={errors.reason?.message}
          aria-required="true"
          {...register('reason')}
        />
        <div className="flex justify-end gap-2">
          <Button variant="outline" onClick={onClose} disabled={isPending}>
            취소
          </Button>
          <Button
            type="submit"
            variant="danger"
            disabled={!isValid || isPending}
            loading={isPending}
          >
            반려 확정
          </Button>
        </div>
      </form>
    </Modal>
  );
}
