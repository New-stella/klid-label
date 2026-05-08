import { forwardRef, useId, type SelectHTMLAttributes } from 'react';

import { cn } from '@/lib/cn';

export interface SelectOption {
  value: string;
  label: string;
  disabled?: boolean;
}

export interface SelectProps extends SelectHTMLAttributes<HTMLSelectElement> {
  label?: string;
  error?: string;
  hint?: string;
  hideLabel?: boolean;
  options: SelectOption[];
  placeholder?: string;
}

export const Select = forwardRef<HTMLSelectElement, SelectProps>(function Select(
  { label, error, hint, hideLabel, options, placeholder, id, className, ...rest },
  ref,
) {
  const autoId = useId();
  const fieldId = id ?? autoId;
  const errorId = error ? `${fieldId}-error` : undefined;
  const hintId = hint && !error ? `${fieldId}-hint` : undefined;
  const describedBy = errorId ?? hintId;

  return (
    <div className="flex flex-col gap-1">
      {label && (
        <label
          htmlFor={fieldId}
          className={cn(
            'text-body font-medium text-gray-700',
            hideLabel && 'sr-only',
          )}
        >
          {label}
        </label>
      )}
      <select
        ref={ref}
        id={fieldId}
        aria-invalid={error ? true : undefined}
        aria-describedby={describedBy}
        className={cn(
          'h-10 w-full rounded-md border bg-white px-3 text-body text-gray-900 outline-none transition-colors duration-100 focus-visible:ring-2 focus-visible:ring-primary-500 disabled:bg-gray-50 disabled:opacity-60',
          error
            ? 'border-danger focus-visible:border-danger'
            : 'border-gray-300 focus-visible:border-primary-500',
          className,
        )}
        {...rest}
      >
        {placeholder && (
          <option value="" disabled hidden>
            {placeholder}
          </option>
        )}
        {options.map((opt) => (
          <option key={opt.value} value={opt.value} disabled={opt.disabled}>
            {opt.label}
          </option>
        ))}
      </select>
      {error ? (
        <span id={errorId} role="alert" className="text-sub text-danger">
          {error}
        </span>
      ) : hint ? (
        <span id={hintId} className="text-sub text-gray-500">
          {hint}
        </span>
      ) : null}
    </div>
  );
});
