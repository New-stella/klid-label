import { forwardRef, useId, type InputHTMLAttributes, type ReactNode } from 'react';

import { cn } from '@/lib/cn';

export interface CheckboxProps extends Omit<InputHTMLAttributes<HTMLInputElement>, 'type'> {
  label?: ReactNode;
  error?: string;
  indeterminate?: boolean;
}

export const Checkbox = forwardRef<HTMLInputElement, CheckboxProps>(function Checkbox(
  { label, error, indeterminate, id, className, ...rest },
  ref,
) {
  const autoId = useId();
  const fieldId = id ?? autoId;
  const errorId = error ? `${fieldId}-error` : undefined;

  const setRef = (el: HTMLInputElement | null) => {
    if (el) el.indeterminate = !!indeterminate;
    if (typeof ref === 'function') ref(el);
    else if (ref) ref.current = el;
  };

  return (
    <span className="inline-flex flex-col gap-1">
      <span className="inline-flex items-center gap-2">
        <input
          ref={setRef}
          id={fieldId}
          type="checkbox"
          aria-invalid={error ? true : undefined}
          aria-describedby={errorId}
          className={cn(
            'h-4 w-4 rounded border-gray-300 text-primary-600 focus-visible:ring-2 focus-visible:ring-primary-500',
            className,
          )}
          {...rest}
        />
        {label && (
          <label htmlFor={fieldId} className="cursor-pointer select-none text-body text-gray-700">
            {label}
          </label>
        )}
      </span>
      {error && (
        <span id={errorId} role="alert" className="text-sub text-danger">
          {error}
        </span>
      )}
    </span>
  );
});
