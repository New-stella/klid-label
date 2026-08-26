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
import { FieldCounter } from '@/components/common/FieldCounter';
import { Field, FieldDescription, FieldError, FieldLabel } from '@/components/common/Field';
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
   * 대상 **작업 묶음**의 사용자 노출명(예: 오토라벨링). 기술 코드는 화면에 넣지 않는다.
   *
   * 문자열만 받는다 — 이 모달은 묶음 코드를 해석하지 않으므로 표시명 판정이 호출부(단일 원천
   * `bundleLabel`)에 남는다. 여기서 코드→이름을 다시 정하면 표가 둘이 된다.
   */
  bundleLabel: string;
  /**
   * 묶음에 속한 작업의 노출명을 이어 붙인 부제(예: AI 탐지 · AI 분할 · 트랙 보간).
   *
   * `bundleLabel` 과 **같은 원칙**이라 문자열만 받는다 — 묶음 코드→멤버 표를 여기서 다시 들면
   * 그 표가 둘이 된다. 멤버가 하나뿐인 묶음처럼 부제가 필요 없으면 넘기지 않는다(줄 자체가 없다).
   */
  bundleMembers?: string;
  loading?: boolean;
  onClose(): void;
  onConfirm(reason: string): void;
}

export function BatchStageSkipModal({
  open,
  bundleLabel,
  bundleMembers,
  loading,
  onClose,
  onConfirm,
}: BatchStageSkipModalProps) {
  const {
    register,
    handleSubmit,
    reset,
    watch,
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

  /**
   * 카운터가 세는 것은 **입력 원문의 길이**다(트림 후가 아니다).
   *
   * 상한을 강제하는 `maxLength` 가 원문 기준이라, 트림 길이를 보여주면 앞뒤 공백을 넣은 사용자에게
   * "아직 여유가 있다"고 말하면서 입력은 막히는 어긋남이 생긴다.
   */
  const reasonLength = watch('reason')?.length ?? 0;

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
      description={`${bundleLabel} 작업을 건너뛰고 다음 작업으로 진행합니다.`}
      size="md"
    >
      <form
        onSubmit={handleSubmit(onSubmit)}
        className="flex flex-col gap-3"
        data-testid="batch-stage-skip-modal"
        noValidate
      >
        {/* 무엇을 건너뛰는지 제목 문자열에만 두지 않는다 — 확인 직전에 대상이 눈에 남아야 한다. */}
        <div className="flex flex-wrap items-center gap-2">
          <span className="text-label text-gray-600">대상 묶음</span>
          <span className="inline-flex items-center rounded-sm bg-gray-100 px-2.5 py-0.5 text-label text-gray-800">
            {bundleLabel}
          </span>
          {bundleMembers && <span className="text-caption text-gray-600">{bundleMembers}</span>}
        </div>
        <Field>
          {/* 글자 수는 라벨 줄 오른쪽에 둔다 — 도움말이 두 줄로 접혀도 숫자가 문단 중간에 뜨지 않는다. */}
          {/* 필수 표식은 눈(빨간 *)과 보조기술(sr-only '(필수)') 양쪽에 필요하다. 아래
              `aria-required` 는 컨트롤에만 붙어 라벨을 훑는 사용자에게는 보이지 않는다 — 둘 다 둔다. */}
          <div className="flex items-baseline justify-between gap-4">
            <FieldLabel required>건너뛰기 사유</FieldLabel>
            <FieldCounter current={reasonLength} max={SKIP_REASON_MAX_LENGTH} />
          </div>
          <Textarea
            className="min-h-[120px]"
            aria-required="true"
            maxLength={SKIP_REASON_MAX_LENGTH}
            {...register('reason')}
          />
          <FieldDescription>
            배치 이력에 그대로 남습니다. 공백만으로는 저장되지 않습니다.
          </FieldDescription>
          <FieldError>{errors.reason?.message}</FieldError>
        </Field>
        <div className="flex justify-end gap-2">
          <Button variant="secondary" onClick={handleClose} disabled={loading}>
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
