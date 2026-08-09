import { zodResolver } from '@hookform/resolvers/zod';
import { useForm } from 'react-hook-form';
import { z } from 'zod';

import { Field, FieldError, FieldLabel } from '@/components/common/Field';
import { Button } from '@/components/common/Button';
import { Modal } from '@/components/common/Modal';
import { Textarea } from '@/components/common/Textarea';

/**
 * 반려 사유 스키마 — UI/UX §4-12 정합 (1~500자 필수).
 *
 * 사용자 노출 문구는 확정 용어 **반려**다(사양 SCREEN-023). 식별자·API 필드
 * (`reject`/`REJECTED`/`rejectReason`)는 계약이라 그대로 둔다.
 */
const rejectSchema = z.object({
  reason: z.string().min(1, '반려 사유를 입력하세요').max(500, '최대 500자'),
});
type RejectForm = z.infer<typeof rejectSchema>;

export interface RejectReasonModalProps {
  open: boolean;
  loading?: boolean;
  onClose(): void;
  onConfirm(reason: string): void;
}

/**
 * 증강 결과 반려 사유 입력 모달.
 *
 * 보안:
 * - reason은 zod min 1 / max 500 검증 (security.md — Input Validation).
 * - BE도 동일 검증 (이중 방어).
 */
export function RejectReasonModal({ open, loading, onClose, onConfirm }: RejectReasonModalProps) {
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

  const handleClose = () => {
    reset({ reason: '' });
    onClose();
  };

  const onSubmit = (values: RejectForm) => {
    onConfirm(values.reason);
    reset({ reason: '' });
  };

  return (
    <Modal
      open={open}
      onClose={handleClose}
      title="반려 사유 입력"
      description="이 증강 결과를 반려하는 사유를 입력하세요."
      size="md"
    >
      <form onSubmit={handleSubmit(onSubmit)} className="flex flex-col gap-3" noValidate>
        <Field>
          <FieldLabel>반려 사유</FieldLabel>
          <Textarea className="min-h-[154px]" aria-required="true" {...register('reason')} />
          <FieldError>{errors.reason?.message}</FieldError>
        </Field>
        <div className="flex justify-end gap-2">
          <Button variant="outline" onClick={handleClose} disabled={loading}>
            취소
          </Button>
          <Button type="submit" variant="danger" disabled={!isValid || loading} loading={loading}>
            반려 확정
          </Button>
        </div>
      </form>
    </Modal>
  );
}
