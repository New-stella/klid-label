import { forwardRef, type InputHTMLAttributes } from 'react';

import { cn } from '@/lib/cn';
import { KRDS_FOCUS } from '@/lib/focusRing';

import { useFieldControl } from './fieldContext';

/**
 * 공통 텍스트 입력 프리미티브 (UI-002).
 *
 * `label`/`hint`/`error` 를 자체적으로 두지 않는 순수 프리미티브다 — 라벨·설명·오류 문구의
 * 배치는 `Field` 계열 조립부(UI-099)가 전담한다. 우측 아이콘·액션이 필요하면 내장 슬롯 prop
 * 대신 바깥에서 relative 컨테이너 + absolute 배치로 조합한다.
 *
 * `aria-invalid` 를 직접 전달하면 그 값만으로 destructive 보더·링이 즉시 반영되고, Field
 * 안에서 `FieldError` 가 렌더되면 같은 처리가 자동으로 걸린다(문구 자체는 렌더하지 않는다).
 */
export type InputProps = InputHTMLAttributes<HTMLInputElement>;

export const Input = forwardRef<HTMLInputElement, InputProps>(function Input(
  { id, className, 'aria-describedby': ariaDescribedBy, 'aria-invalid': ariaInvalid, ...rest },
  ref,
) {
  const {
    id: inputId,
    describedBy,
    invalid,
    hasError,
  } = useFieldControl({
    id,
    'aria-describedby': ariaDescribedBy,
    'aria-invalid': ariaInvalid,
  });

  return (
    <input
      ref={ref}
      id={inputId}
      aria-invalid={invalid}
      aria-describedby={describedBy}
      className={cn(
        // 모서리 반경은 `rounded-md`(6px) — 폼 컨트롤 5종(Input/Select/Textarea/FileInput/
        // DatePicker) 공통 값이다. 이것만 `rounded-lg`(8px)라 같은 폼 안에서 나란히 놓였을 때
        // 혼자 둥글어 보였다. 가드: src/test/formControlRadius.test.ts
        'h-11 w-full rounded-md border bg-white px-3 text-body text-gray-900 outline-hidden transition-colors duration-100 placeholder:text-gray-400 disabled:bg-gray-50 disabled:opacity-60',
        KRDS_FOCUS,
        // 경계는 시안 `--border-strong`(= `--n-4` = gray-400). 한 단 옅은 gray-300 은 흰 배경
        // 위 2.01:1 이라 WCAG 1.4.11(비텍스트 3:1) 미달이고 gray-400 은 3.08:1 로 통과한다.
        // Textarea·Button secondary 가 같은 근거로 이미 gray-400 이라 한 폼 안에서 값이
        // 갈리고 있었다 — 시안 정합과 접근성과 일관성이 같은 방향이다.
        hasError
          ? 'border-danger focus-visible:border-danger'
          : 'border-gray-400 focus-visible:border-primary-500',
        className,
      )}
      {...rest}
    />
  );
});
