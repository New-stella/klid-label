// 공지 작성 폼 — 사양 SCREEN-036 '작성 폼' 섹션 본체.
//
// ★ 왜 수정 화면(SCREEN-037)의 `NoticeFormSection` 과 한 컴포넌트가 아닌가
//   필드 구성·검증 규칙은 두 화면이 같지만, 각 화면 설계가 요구하는 **표시 요소가 갈린다** —
//   작성 화면만 제목 문자 수 카운터(UI-115)·중요 공지 부가 설명을 갖는다.
//   그래서 표시 계층은 화면별로 두고, **판정(검증)은 공용 `noticeSchema` 한 곳**에서만 한다.
//   검증 규칙을 여기서 다시 정의하지 않는다 — 스키마가 단일 진실원이다.
//
//   ⚠ **필드 도움말은 더 이상 작성 화면 전용이 아니다** — SCREEN-037 확정으로 수정 폼도
//     제목 `필수 · 최대 200자` / 내용 `필수` 캡션을 갖는다. 같은 정보를 두 화면이 다르게
//     말하지 않도록 제목 캡션 문구는 두 폼이 **같은 문장**을 쓴다(구 주석의 '작성 화면만
//     … 필드 도움말' 서술은 폐기).
//
// 첨부파일 관리는 이 폼에 없다 — 게시글 id 가 발급되기 전이라 업로드 대상이 없고,
// 첨부는 저장 후 수정 화면에서 추가한다.
import { zodResolver } from '@hookform/resolvers/zod';
import { Controller, useForm, useWatch } from 'react-hook-form';

import { Button } from '@/components/common/Button';
import { Checkbox } from '@/components/common/Checkbox';
import {
  Field,
  FieldContent,
  FieldDescription,
  FieldError,
  FieldLabel,
} from '@/components/common/Field';
import { FieldCounter } from '@/components/common/FieldCounter';
import { Input } from '@/components/common/Input';
import { Textarea } from '@/components/common/Textarea';

import { noticeSchema, type NoticeFormValues } from '../schemas';
import type { NoticeForm } from '../types';

export interface NoticeCreateFormProps {
  submitting?: boolean;
  onSubmit: (form: NoticeForm) => void;
  /** 취소 목적지는 화면이 정한다 — 작성 화면은 목록으로 돌아간다. */
  onCancel: () => void;
}

const EMPTY_FORM: NoticeFormValues = {
  title: '',
  content: '',
  pinned: false,
};

/**
 * 카운터가 표시하는 상한. 판정은 `noticeSchema` 의 `max(200)` 이 하고 여기 값은 **표시 전용**이다
 * (입력 자체를 잘라내지 않는다 — 상한 초과는 검증 메시지로 알린다).
 */
const TITLE_MAX_DISPLAY = 200;

export function NoticeCreateForm({ submitting, onSubmit, onCancel }: NoticeCreateFormProps) {
  const {
    register,
    control,
    handleSubmit,
    formState: { errors },
  } = useForm<NoticeFormValues>({
    resolver: zodResolver(noticeSchema),
    defaultValues: EMPTY_FORM,
  });

  // 카운터 표시용 구독 — 제목 필드만 좁혀 본다(폼 전체 watch 는 무관한 필드 입력마다 다시 그린다).
  const title = useWatch({ control, name: 'title' }) ?? '';

  const submit = handleSubmit((form) => {
    onSubmit({
      title: form.title.trim(),
      content: form.content,
      pinned: form.pinned,
    });
  });

  return (
    // 폼 폭 760px 은 단일 컬럼 폼의 줄 길이 가독성을 위한 의도적 제한이다(SCREEN-036 디자인).
    // 목록·캔버스 화면의 "콘텐츠 폭 무제한" 원칙과 충돌하지 않는다.
    <form className="flex max-w-[760px] flex-col gap-6" onSubmit={submit} noValidate>
      <Field>
        {/* 라벨과 카운터를 한 줄에 양끝 정렬 — 카운터는 라벨 행의 우측 슬롯이다. */}
        <div className="flex items-baseline justify-between gap-2">
          <FieldLabel required>제목</FieldLabel>
          <FieldCounter current={title.length} max={TITLE_MAX_DISPLAY} />
        </div>
        <Input
          type="text"
          placeholder="공지 제목을 입력하세요. (최대 200자)"
          {...register('title')}
        />
        <FieldDescription className="text-gray-600">
          필수 · 최대 200자 · 앞뒤 공백은 저장 시 자동 제거됩니다.
        </FieldDescription>
        <FieldError>{errors.title?.message}</FieldError>
      </Field>

      <Field>
        <FieldLabel required>내용</FieldLabel>
        <Textarea
          placeholder="공지 내용을 입력하세요."
          className="min-h-[168px] resize-y"
          {...register('content')}
        />
        <FieldError>{errors.content?.message}</FieldError>
      </Field>

      {/* Checkbox 는 네이티브 input 이 아니라 boolean|'indeterminate' 를 다루는 컨트롤이라
          register 스프레드가 아니라 Controller 로 잇는다. */}
      <Field orientation="horizontal" className="items-start">
        <Controller
          name="pinned"
          control={control}
          render={({ field }) => (
            <Checkbox
              // 라벨이 44px 행을 만들지 않는 배치(라벨+설명 2행)라 컨트롤이 직접 히트영역을 예약한다.
              className="min-w-11"
              checked={field.value}
              onCheckedChange={(v) => field.onChange(v === true)}
              onBlur={field.onBlur}
              ref={field.ref}
            />
          )}
        />
        <FieldContent className="pt-3">
          <FieldLabel className="group-data-[orientation=horizontal]/field:min-h-0">
            중요 공지
          </FieldLabel>
          <FieldDescription className="text-gray-600">
            체크하면 게시판 목록 최상단에 고정 표시됩니다.
          </FieldDescription>
        </FieldContent>
      </Field>

      <div className="mt-2 flex items-center justify-end gap-2 border-t border-gray-100 pt-4">
        <Button type="button" variant="outline" onClick={onCancel} disabled={submitting}>
          취소
        </Button>
        <Button type="submit" variant="primary" loading={submitting}>
          작성
        </Button>
      </div>
    </form>
  );
}
