// 공지 작성/수정 폼 — 사양 SCREEN-036 '작성 폼' · SCREEN-037 '수정 폼' 공통 본체.
//
// 두 화면의 필드 구성·검증 규칙이 동일하다고 사양이 명시하므로(SCREEN-037 '작성 화면과 동일 규칙')
// 폼을 한 곳에 두고 버튼 문구·취소 목적지만 화면이 정한다. 첨부파일 관리는 여기 두지 않는다 —
// 작성 화면에는 그 섹션 자체가 없고(게시글 id 미발급), 수정 화면만 별도 영역으로 갖는다.
import { zodResolver } from '@hookform/resolvers/zod';
import { Controller, useForm } from 'react-hook-form';

import { Button } from '@/components/common/Button';
import { Checkbox } from '@/components/common/Checkbox';
import {
  Field,
  FieldDescription,
  FieldError,
  FieldLabel,
} from '@/components/common/Field';
import { Input } from '@/components/common/Input';
import { Textarea } from '@/components/common/Textarea';

import { noticeSchema, type NoticeFormValues } from '../schemas';
import type { NoticeForm } from '../types';

export interface NoticeFormSectionProps {
  /** 초기값 — 수정 화면이 상세 조회 결과를 넘긴다. 작성 화면은 생략(빈 폼). */
  defaultValues?: NoticeFormValues;
  /** 제출 버튼 문구 — 작성 화면 '작성' / 수정 화면 '저장'. */
  submitLabel: string;
  submitting?: boolean;
  onSubmit: (form: NoticeForm) => void;
  /** 취소 목적지는 화면이 정한다 — 작성=목록, 수정=해당 게시글 상세. */
  onCancel: () => void;
}

const EMPTY_FORM: NoticeFormValues = {
  title: '',
  content: '',
  pinned: false,
};

export function NoticeFormSection({
  defaultValues,
  submitLabel,
  submitting,
  onSubmit,
  onCancel,
}: NoticeFormSectionProps) {
  const {
    register,
    control,
    handleSubmit,
    formState: { errors },
  } = useForm<NoticeFormValues>({
    resolver: zodResolver(noticeSchema),
    defaultValues: defaultValues ?? EMPTY_FORM,
  });

  const submit = handleSubmit((form) => {
    onSubmit({
      title: form.title.trim(),
      content: form.content,
      pinned: form.pinned,
    });
  });

  return (
    <form className="space-y-5" onSubmit={submit}>
      <Field className="gap-1.5">
        <FieldLabel required>제목</FieldLabel>
        <Input
          type="text"
          placeholder="공지 제목을 입력하세요. (최대 200자)"
          {...register('title')}
        />
        {/* [design: SCREEN-037] 도움말 캡션 — 작성 폼(SCREEN-036)과 같은 정보를 같은 톤으로
            말한다. `FieldDescription` 이 Field 컨텍스트를 통해 입력의 aria-describedby 에
            자동으로 연결되므로 시각 표기로만 남지 않는다. */}
        <FieldDescription
          data-testid="notice-title-description"
          className="text-gray-600"
        >
          필수 · 최대 200자 · 앞뒤 공백은 저장 시 자동 제거됩니다.
        </FieldDescription>
        <FieldError>{errors.title?.message}</FieldError>
      </Field>

      <Field className="gap-1.5">
        <FieldLabel required>내용</FieldLabel>
        <Textarea
          placeholder="공지 내용을 입력하세요."
          className="min-h-[236px] resize-y"
          {...register('content')}
        />
        <FieldDescription
          data-testid="notice-content-description"
          className="text-gray-600"
        >
          필수
        </FieldDescription>
        <FieldError>{errors.content?.message}</FieldError>
      </Field>

      {/* Checkbox 는 네이티브 input 이 아니라 boolean|'indeterminate' 를 다루는 컨트롤이라
          register 스프레드가 아니라 Controller 로 잇는다. */}
      <Field orientation="horizontal">
        <Controller
          name="pinned"
          control={control}
          render={({ field }) => (
            <Checkbox
              checked={field.value}
              onCheckedChange={(v) => field.onChange(v === true)}
              onBlur={field.onBlur}
              ref={field.ref}
            />
          )}
        />
        <FieldLabel>중요 공지</FieldLabel>
      </Field>

      <div className="flex items-center justify-end gap-2 border-t border-gray-200 pt-4">
        <Button type="button" variant="outline" onClick={onCancel} disabled={submitting}>
          취소
        </Button>
        <Button type="submit" variant="primary" loading={submitting}>
          {submitLabel}
        </Button>
      </div>
    </form>
  );
}
