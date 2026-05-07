import { forwardRef, useId, type TextareaHTMLAttributes } from 'react';

import { cn } from '@/lib/cn';

export interface TextareaProps extends TextareaHTMLAttributes<HTMLTextAreaElement> {
  label?: string;
  error?: string;
  hint?: string;
  hideLabel?: boolean;
}

export const Textarea = forwardRef<HTMLTextAreaElement, TextareaProps>(function Textarea(
  { label, error, hint, hideLabel, id, className, rows = 4, ...rest },
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
            'text-body font-medium text-primary',
            hideLabel && 'sr-only',
          )}
        >
          {label}
        </label>
      )}
      <textarea
        ref={ref}
        id={fieldId}
        rows={rows}
        aria-invalid={error ? true : undefined}
        aria-describedby={describedBy}
        className={cn(
          'w-full rounded border bg-white px-3 py-2 text-body outline-none transition-colors duration-100 focus-visible:ring-2 focus-visible:ring-accent disabled:bg-bgLight disabled:opacity-60',
          error ? 'border-danger' : 'border-border focus-visible:border-accent',
          className,
        )}
        {...rest}
      />
      {error ? (
        <span id={errorId} role="alert" className="text-sub text-danger">
          {error}
        </span>
      ) : hint ? (
        <span id={hintId} className="text-sub text-neutral">
          {hint}
        </span>
      ) : null}
    </div>
  );
});
