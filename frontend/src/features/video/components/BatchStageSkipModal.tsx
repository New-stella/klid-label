// 배치 단계 건너뛰기 사유 입력 모달. [@design API-198] [@design SCREEN-009]
//
// 사유가 **필수**인 이유: 건너뛰면 그 영상의 시계열 서술·자동 라벨이 비는데, 나중에 "왜 이 영상만
// 비어 있나"를 되짚을 근거가 남지 않으면 그 결정이 사고인지 판단인지 구분되지 않는다. 서버도
// 같은 제약(@NotBlank + 500자)을 걸므로 화면은 이중 방어일 뿐 유일한 방어선이 아니다.

import { useEffect, useRef } from 'react';
import { zodResolver } from '@hookform/resolvers/zod';
import { useForm } from 'react-hook-form';
import { z } from 'zod';

import { Button } from '@/components/common/Button';
import { Field, FieldError, FieldLabel } from '@/components/common/Field';
import { Modal } from '@/components/common/Modal';
import { Textarea } from '@/components/common/Textarea';

/** 사유 길이 상한 — BE `BatchStageSkipRequest#reason` 의 `@Size(max=500)` 과 같은 값. */
const SKIP_REASON_MAX_LENGTH = 500;

/**
 * 스킵 사유 스키마 — BE `@NotBlank` + `@Size(max=500)` 과 **같은 제약**.
 *
 * `.trim()` 을 먼저 두어 공백만 입력한 값을 거르며(`min(1)` 만 걸면 공백 한 글자가 통과해 사용자가
 * 전송 후에야 400 을 본다), 트림된 값이 그대로 전송되므로 길이 상한도 서버와 같은 기준이 된다.
 */
const skipSchema = z.object({
  reason: z
    .string()
    .trim()
    .min(1, '건너뛰기 사유를 입력하세요')
    .max(SKIP_REASON_MAX_LENGTH, `최대 ${SKIP_REASON_MAX_LENGTH}자`),
});
type SkipForm = z.infer<typeof skipSchema>;

export interface BatchStageSkipModalProps {
  open: boolean;
  /**
   * 대상 **작업 묶음**의 사용자 노출명(예: 'AI 탐지 · AI 분할 · 보간'). 기술 코드는 화면에 넣지 않는다.
   *
   * 문자열만 받는다 — 이 모달은 묶음 코드를 해석하지 않으므로 표시명 판정이 호출부(단일 원천
   * `bundleLabel`)에 남는다. 여기서 코드→이름을 다시 정하면 표가 둘이 된다.
   */
  bundleLabel: string;
  loading?: boolean;
  onClose(): void;
  onConfirm(reason: string): void;
}

export function BatchStageSkipModal({
  open,
  bundleLabel,
  loading,
  onClose,
  onConfirm,
}: BatchStageSkipModalProps) {
  const {
    register,
    handleSubmit,
    reset,
    formState: { errors, isValid },
  } = useForm<SkipForm>({
    resolver: zodResolver(skipSchema),
    mode: 'onChange',
    defaultValues: { reason: '' },
  });

  /**
   * 동기 락 — 연타 방어의 실제 방어선.
   *
   * `loading`(뮤테이션 pending)만으로는 부족하다. react-hook-form 의 `handleSubmit` 은 비동기라
   * 같은 tick 의 클릭들이 전부 `onSubmit` 까지 도달한 뒤에야 첫 요청의 상태가 반영된다.
   */
  const submitLockRef = useRef(false);

  // 닫아도 컴포넌트가 마운트된 채 남으므로, 다시 열릴 때 락과 입력을 초기화한다.
  useEffect(() => {
    if (open) {
      submitLockRef.current = false;
      reset({ reason: '' });
    }
  }, [open, reset]);

  const handleClose = () => {
    if (loading) return;
    reset({ reason: '' });
    onClose();
  };

  const onSubmit = (values: SkipForm) => {
    if (submitLockRef.current) return;
    submitLockRef.current = true;
    onConfirm(values.reason);
  };

  return (
    <Modal
      open={open}
      onClose={handleClose}
      title={`${bundleLabel} 작업 건너뛰기`}
      description={`${bundleLabel} 작업을 건너뛰고 다음 작업으로 진행합니다. 사유는 배치 이력에 남습니다.`}
      size="md"
    >
      <form
        onSubmit={handleSubmit(onSubmit)}
        className="flex flex-col gap-3"
        data-testid="batch-stage-skip-modal"
        noValidate
      >
        <Field>
          <FieldLabel>건너뛰기 사유</FieldLabel>
          <Textarea
            className="min-h-[120px]"
            aria-required="true"
            maxLength={SKIP_REASON_MAX_LENGTH}
            {...register('reason')}
          />
          <FieldError>{errors.reason?.message}</FieldError>
        </Field>
        <div className="flex justify-end gap-2">
          <Button variant="outline" onClick={handleClose} disabled={loading}>
            취소
          </Button>
          <Button type="submit" variant="primary" disabled={!isValid || loading} loading={loading}>
            건너뛰기
          </Button>
        </div>
      </form>
    </Modal>
  );
}
