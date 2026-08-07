import { useEffect, useRef } from 'react';
import { zodResolver } from '@hookform/resolvers/zod';
import { useForm } from 'react-hook-form';
import { z } from 'zod';

import { Field, FieldError, FieldLabel } from '@/components/common/Field';
import { Button } from '@/components/common/Button';
import { Modal } from '@/components/common/Modal';
import { Textarea } from '@/components/common/Textarea';

/** 복구 사유 길이 상한 — BE `AugmentRestoreRequest#reason` 의 `@Size(max=500)` 과 같은 값. */
const RESTORE_REASON_MAX_LENGTH = 500;

/**
 * 복구 사유 스키마 — BE `@NotBlank` + `@Size(max=500)` 과 **같은 제약**.
 *
 * `.trim()` 을 먼저 두어 `" "`(공백만)를 거부한다. `min(1)` 만 걸면 공백 한 글자가 통과해
 * 사용자가 전송 후에야 400 을 보게 된다(제출 버튼이 켜져 있으니 화면이 거짓말을 한 셈).
 * 트림된 값이 그대로 전송되므로 길이 상한도 트림 기준으로 BE 와 일치한다.
 */
const restoreSchema = z.object({
  reason: z
    .string()
    .trim()
    .min(1, '복구 사유를 입력하세요')
    .max(RESTORE_REASON_MAX_LENGTH, `최대 ${RESTORE_REASON_MAX_LENGTH}자`),
});
type RestoreForm = z.infer<typeof restoreSchema>;

export interface RestoreReasonModalProps {
  open: boolean;
  loading?: boolean;
  onClose(): void;
  onConfirm(reason: string): void;
}

/**
 * 폐기(반려) 복구 사유 입력 모달.
 *
 * 거부 사유 모달(`RejectReasonModal`)과 **의미가 반대**라 재사용하지 않는다 — 그 컴포넌트는
 * 제목("거부 사유 입력")·확정 버튼("거부 확정")·강조색(danger)이 내부 하드코딩이라, 그대로 쓰면
 * "거부 확정" 버튼으로 복구를 실행하는 화면이 된다.
 *
 * 보안:
 * - 입력 검증(CWE-20): 위 스키마가 BE 와 동일 제약을 건다(이중 방어).
 * - XSS(CWE-79): 사유는 JSX 자동 이스케이프로만 렌더된다(`dangerouslySetInnerHTML` 미사용).
 * - 연타(중복 제출): 서버측 중복 차단·속도 제한을 두지 않는 정책이라 `loading` 바인딩이
 *   유일한 방어선이다.
 */
export function RestoreReasonModal({ open, loading, onClose, onConfirm }: RestoreReasonModalProps) {
  const {
    register,
    handleSubmit,
    reset,
    formState: { errors, isValid },
  } = useForm<RestoreForm>({
    resolver: zodResolver(restoreSchema),
    mode: 'onChange',
    defaultValues: { reason: '' },
  });

  /**
   * 동기 락 — 연타 방어의 **실제 방어선**.
   *
   * `loading`(뮤테이션 pending) 만으로는 부족하다. react-hook-form 의 `handleSubmit` 은
   * **비동기**(검증이 마이크로태스크)라 같은 tick 에 들어온 클릭들이 전부 `onSubmit` 까지 도달한
   * 뒤에야 첫 요청의 상태 변화가 반영된다 — 버튼 disabled 는 이미 늦다. 서버측 중복 차단·속도
   * 제한을 두지 않는 정책이므로 이 락이 없으면 복구 요청이 그대로 여러 번 나간다.
   */
  const submitLockRef = useRef(false);

  // 모달을 다시 열면(닫혀도 컴포넌트는 마운트 상태로 남는다) 락을 푼다.
  useEffect(() => {
    if (open) submitLockRef.current = false;
  }, [open]);

  const handleClose = () => {
    reset({ reason: '' });
    onClose();
  };

  const onSubmit = (values: RestoreForm) => {
    if (submitLockRef.current) return;
    submitLockRef.current = true;
    onConfirm(values.reason);
    reset({ reason: '' });
  };

  return (
    <Modal
      open={open}
      onClose={handleClose}
      title="복구 사유 입력"
      description="이 결과물을 다시 활용 결정 대기로 되돌립니다. 되돌린 이력이 남으므로 사유를 입력하세요."
      size="md"
    >
      <form
        onSubmit={handleSubmit(onSubmit)}
        className="flex flex-col gap-3"
        data-testid="augment-restore-modal"
        noValidate
      >
        <Field>
          <FieldLabel>복구 사유</FieldLabel>
          <Textarea className="min-h-[154px]" aria-required="true" {...register('reason')} />
          <FieldError>{errors.reason?.message}</FieldError>
        </Field>
        <div className="flex justify-end gap-2">
          <Button variant="outline" onClick={handleClose} disabled={loading}>
            취소
          </Button>
          <Button type="submit" variant="primary" disabled={!isValid || loading} loading={loading}>
            복구 확정
          </Button>
        </div>
      </form>
    </Modal>
  );
}
