import { forwardRef, type InputHTMLAttributes } from 'react';
import { format, parse, isValid } from 'date-fns';
import { ko } from 'date-fns/locale';

import { cn } from '@/lib/cn';
import { KRDS_FOCUS } from '@/lib/focusRing';

import { useFieldControl } from './fieldContext';

export interface DatePickerProps
  extends Omit<InputHTMLAttributes<HTMLInputElement>, 'type' | 'value' | 'onChange'> {
  value?: string; // ISO YYYY-MM-DD
  onChange?: (value: string) => void;
  min?: string;
  max?: string;
  /**
   * 값이 있을 때 입력 아래에 한국어 표기(예: 2026년 8월 7일)를 덧붙인다.
   * 라벨·설명·오류 문구는 `Field` 계열 조립부(UI-099)가 전담하므로 이 표시만 남는다.
   */
  showLocalizedDisplay?: boolean;
}

/**
 * 한국어 locale 표시 + native date input 기반.
 * value/onChange는 YYYY-MM-DD 문자열 사용.
 *
 * 라벨·설명·오류 문구는 갖지 않는다 — `Field` 계열 조립부(UI-099)가 전담한다.
 */
export const DatePicker = forwardRef<HTMLInputElement, DatePickerProps>(function DatePicker(
  {
    value,
    onChange,
    id,
    className,
    min,
    max,
    showLocalizedDisplay = true,
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

  // 한국어 locale로 placeholder/포맷 표시
  const localizedDisplay = (() => {
    if (!value) return '';
    const d = parse(value, 'yyyy-MM-dd', new Date());
    return isValid(d) ? format(d, 'yyyy년 M월 d일', { locale: ko }) : '';
  })();

  return (
    <>
      <input
        ref={ref}
        id={fieldId}
        type="date"
        lang="ko"
        value={value ?? ''}
        onChange={(e) => onChange?.(e.target.value)}
        min={min}
        max={max}
        aria-invalid={invalid}
        aria-describedby={describedBy}
        title={localizedDisplay}
        className={cn(
          'h-11 w-full rounded-md border bg-white px-3 text-body text-gray-900 outline-none disabled:bg-gray-50 disabled:opacity-60',
          KRDS_FOCUS,
          hasError
            ? 'border-danger focus-visible:border-danger'
            : 'border-gray-300 focus-visible:border-primary-500',
          className,
        )}
        {...rest}
      />
      {showLocalizedDisplay && value && !hasError && (
        <span className="text-sub text-gray-500" data-testid="datepicker-display">
          {localizedDisplay}
        </span>
      )}
    </>
  );
});
