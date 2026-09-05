import { forwardRef, type TextareaHTMLAttributes } from 'react';

import { cn } from '@/lib/cn';
import { KRDS_FOCUS } from '@/lib/focusRing';

import { useFieldControl } from './fieldContext';

/**
 * 공통 멀티라인 텍스트 입력 프리미티브 (UI-027).
 *
 * `label`/`hint`/`error` 를 자체적으로 두지 않는다 — 문구 배치는 `Field` 계열 조립부(UI-099)가
 * 전담한다.
 *
 * **자동 확장**: `field-sizing: content` 로 입력 내용에 따라 높이가 늘어난다. 따라서 고정
 * `rows` 기본값을 두지 않으며(브라우저가 `field-sizing:content` 에서 `rows` 를 크기 계산에
 * 쓰지 않는다), 더 큰 기본 크기가 필요한 호출부는 `className` 으로 `min-h-*` 를 재지정한다.
 *
 * ⚠ **기본 `min-h` 를 올리지 말 것** — 시안의 `min-height` 는 공용 기본값이 아니라 **자리마다
 *   다르다**(실측: 44 · 84 · 88 · 112 · 220px). 44px 를 그대로 쓰는 시안이 실재하고 그 값이
 *   KRDS 터치 타깃 하한이기도 하므로, 더 큰 자리는 위 규약대로 호출부가 재지정한다.
 *
 * **표면**: 좌우 패딩 16px(`--sp-md`)과 테두리 `--border-strong`(gray-400)은 시안 여러 장이
 * 공유하는 값이라 여기가 소유한다. 테두리가 gray-300 이 아닌 이유는 **비텍스트 대비**다 —
 * 흰 배경 위 gray-300 은 2.01:1 로 WCAG 1.4.11(3:1) 미달이고 gray-400 은 3.08:1 로 통과한다
 * (공용 `Button` secondary 가 같은 근거로 이미 gray-400 을 쓴다).
 *
 * **상한**: 공통 `Modal` 은 자체 스크롤 컨테이너가 아니라(`max-h`/`overflow` 없음) 내용이
 * 커지면 다이얼로그가 뷰포트를 넘어 조작 불가가 된다. 그래서 자동 확장을 `max-h-[50vh]` 로
 * 묶고 그 이상은 textarea 자신이 스크롤한다. 호출부 `className` 으로 덮어쓸 수 있다.
 */
export type TextareaProps = TextareaHTMLAttributes<HTMLTextAreaElement>;

export const Textarea = forwardRef<HTMLTextAreaElement, TextareaProps>(function Textarea(
  {
    id,
    className,
    rows,
    'aria-describedby': ariaDescribedBy,
    'aria-invalid': ariaInvalid,
    ...rest
  },
  ref,
) {
  const {
    id: fieldId,
    describedBy,
    invalid,
    hasError,
  } = useFieldControl({
    id,
    'aria-describedby': ariaDescribedBy,
    'aria-invalid': ariaInvalid,
  });

  return (
    <textarea
      ref={ref}
      id={fieldId}
      rows={rows}
      aria-invalid={invalid}
      aria-describedby={describedBy}
      className={cn(
        'min-h-11 max-h-[50vh] w-full rounded-md border bg-white px-4 py-2 text-body text-gray-900 outline-hidden transition-colors duration-100 placeholder:text-gray-400 disabled:bg-gray-50 disabled:opacity-60 [field-sizing:content]',
        KRDS_FOCUS,
        hasError
          ? 'border-danger focus-visible:border-danger'
          : 'border-gray-400 focus-visible:border-primary-500',
        className,
      )}
      {...rest}
    />
  );
});
