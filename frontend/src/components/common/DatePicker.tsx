import { forwardRef, useId, type InputHTMLAttributes } from 'react';
import { format, parse, isValid } from 'date-fns';
import { ko } from 'date-fns/locale';

import { cn } from '@/lib/cn';
import { KRDS_FOCUS } from '@/lib/focusRing';

export interface DatePickerProps
  extends Omit<InputHTMLAttributes<HTMLInputElement>, 'type' | 'value' | 'onChange'> {
  label?: string;
  error?: string;
  hint?: string;
  value?: string; // ISO YYYY-MM-DD
  onChange?: (value: string) => void;
  min?: string;
  max?: string;
}

/**
 * 한국어 locale 표시 + native date input 기반.
 * value/onChange는 YYYY-MM-DD 문자열 사용.
 */
export const DatePicker = forwardRef<HTMLInputElement, DatePickerProps>(function DatePicker(
  { label, error, hint, value, onChange, id, className, min, max, ...rest },
  ref,
) {
  const autoId = useId();
  const fieldId = id ?? autoId;
  const errorId = error ? `${fieldId}-error` : undefined;
  const hintId = hint && !error ? `${fieldId}-hint` : undefined;

  // 한국어 locale로 placeholder/포맷 표시
  const localizedDisplay = (() => {
    if (!value) return '';
    const d = parse(value, 'yyyy-MM-dd', new Date());
    return isValid(d) ? format(d, 'yyyy년 M월 d일', { locale: ko }) : '';
  })();

  return (
    <div className="flex flex-col gap-1">
      {label && (
        <label htmlFor={fieldId} className="text-body font-medium text-gray-700">
          {label}
        </label>
      )}
      <input
        ref={ref}
        id={fieldId}
        type="date"
        lang="ko"
        value={value ?? ''}
        onChange={(e) => onChange?.(e.target.value)}
        min={min}
        max={max}
        aria-invalid={error ? true : undefined}
        aria-describedby={errorId ?? hintId}
        title={localizedDisplay}
        className={cn(
          'h-11 w-full rounded-md border bg-white px-3 text-body text-gray-900 outline-none disabled:bg-gray-50 disabled:opacity-60',
          KRDS_FOCUS,
          error
            ? 'border-danger focus-visible:border-danger'
            : 'border-gray-300 focus-visible:border-primary-500',
          className,
        )}
        {...rest}
      />
      {error ? (
        <span id={errorId} role="alert" className="text-sub text-danger">
          {error}
        </span>
      ) : hint ? (
        <span id={hintId} className="text-sub text-gray-500">
          {hint}
        </span>
      ) : value ? (
        <span className="text-sub text-gray-500" data-testid="datepicker-display">
          {localizedDisplay}
        </span>
      ) : null}
    </div>
  );
});
