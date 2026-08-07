import { useState } from 'react';
import { zodResolver } from '@hookform/resolvers/zod';
import { useForm } from 'react-hook-form';
import { z } from 'zod';

import { Field, FieldError, FieldLabel } from '@/components/common/Field';
import { Button } from '@/components/common/Button';
import { Modal } from '@/components/common/Modal';
import { Textarea } from '@/components/common/Textarea';
import { Input } from '@/components/common/Input';
import { useUiStore } from '@/stores/useUiStore';

import { useAddIssue } from '../hooks/useReviewActions';

const issueSchema = z.object({
  frameId: z.coerce.number().int().min(0),
  description: z.string().min(1, '이슈 설명을 입력하세요').max(1000, '최대 1000자'),
});
type IssueForm = z.infer<typeof issueSchema>;

export interface AddIssueButtonProps {
  reviewId: number;
  defaultFrameId?: number;
}

/**
 * 이슈 추가 버튼 + 모달.
 * UI/UX §4-9 — 프레임 ID + 설명 텍스트만 (캔버스 좌표 마커 X).
 *
 * 보안: zod로 frameId(int>=0), description(1~1000자) 검증.
 */
export function AddIssueButton({ reviewId, defaultFrameId = 0 }: AddIssueButtonProps) {
  const [open, setOpen] = useState(false);
  const pushToast = useUiStore((s) => s.pushToast);

  const {
    register,
    handleSubmit,
    reset,
    formState: { errors, isValid },
  } = useForm<IssueForm>({
    resolver: zodResolver(issueSchema),
    mode: 'onChange',
    defaultValues: { frameId: defaultFrameId, description: '' },
  });

  const { mutate, isPending } = useAddIssue({
    onSuccess: () => {
      pushToast({ variant: 'success', message: '이슈 추가됨' });
      reset({ frameId: defaultFrameId, description: '' });
      setOpen(false);
    },
    onError: () => {
      pushToast({ variant: 'error', message: '이슈 추가 실패' });
    },
  });

  const onSubmit = (values: IssueForm) => {
    mutate({ reviewId, body: { frameId: values.frameId, description: values.description } });
  };

  return (
    <>
      <Button variant="outline" onClick={() => setOpen(true)} data-testid="add-issue-button">
        + 이슈 추가
      </Button>
      <Modal
        open={open}
        onClose={() => setOpen(false)}
        title="이슈 추가"
        description="프레임 단위로 이슈를 기록합니다."
      >
        <form onSubmit={handleSubmit(onSubmit)} className="flex flex-col gap-3" noValidate>
          <Field>
            <FieldLabel>프레임 번호</FieldLabel>
            <Input type="number" min={0} {...register('frameId', { valueAsNumber: true })} />
            <FieldError>{errors.frameId?.message}</FieldError>
          </Field>
          <Field>
            <FieldLabel>이슈 설명</FieldLabel>
            <Textarea className="min-h-[127px]" {...register('description')} />
            <FieldError>{errors.description?.message}</FieldError>
          </Field>
          <div className="flex justify-end gap-2">
            <Button variant="outline" onClick={() => setOpen(false)} disabled={isPending}>
              취소
            </Button>
            <Button
              type="submit"
              variant="primary"
              disabled={!isValid || isPending}
              loading={isPending}
            >
              추가
            </Button>
          </div>
        </form>
      </Modal>
    </>
  );
}
