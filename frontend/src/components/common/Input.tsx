import { forwardRef, useId, type InputHTMLAttributes, type ReactNode } from 'react';

import { cn } from '@/lib/cn';

export interface InputProps extends InputHTMLAttributes<HTMLInputElement> {
  label?: string;
  error?: string;
  hint?: string;
  hideLabel?: boolean;
  rightSlot?: ReactNode;
}

export const Input = forwardRef<HTMLInputElement, InputProps>(function Input(
  { label, error, hint, hideLabel, id, className, rightSlot, ...rest },
  ref,
) {
  const autoId = useId();
  const inputId = id ?? autoId;
  const errorId = error ? `${inputId}-error` : undefined;
  const hintId = hint && !error ? `${inputId}-hint` : undefined;
  const describedBy = errorId ?? hintId;

  return (
    <div className="flex flex-col gap-1">
      {label && (
        <label
          htmlFor={inputId}
          className={cn(
            'text-body font-medium text-gray-700',
            hideLabel && 'sr-only',
          )}
        >
          {label}
        </label>
      )}
      <div className="relative">
        <input
          ref={ref}
          id={inputId}
          aria-invalid={error ? true : undefined}
          aria-describedby={describedBy}
          className={cn(
            'h-10 w-full rounded-md border bg-white px-3 text-body text-gray-900 outline-none transition-colors duration-100 placeholder:text-gray-400 focus-visible:ring-2 focus-visible:ring-primary-500 disabled:bg-gray-50 disabled:opacity-60',
            error
              ? 'border-danger focus-visible:border-danger'
              : 'border-gray-300 focus-visible:border-primary-500',
            className,
          )}
          {...rest}
        />
        {rightSlot && (
          <div className="absolute inset-y-0 right-2 flex items-center">{rightSlot}</div>
        )}
      </div>
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
