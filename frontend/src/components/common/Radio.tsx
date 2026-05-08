import { forwardRef, useId, type InputHTMLAttributes, type ReactNode } from 'react';

import { cn } from '@/lib/cn';

export interface RadioProps extends Omit<InputHTMLAttributes<HTMLInputElement>, 'type'> {
  label?: ReactNode;
}

export const Radio = forwardRef<HTMLInputElement, RadioProps>(function Radio(
  { label, id, className, ...rest },
  ref,
) {
  const autoId = useId();
  const fieldId = id ?? autoId;
  return (
    <span className="inline-flex items-center gap-2">
      <input
        ref={ref}
        id={fieldId}
        type="radio"
        className={cn(
          'h-4 w-4 border-gray-300 text-primary-600 focus-visible:ring-2 focus-visible:ring-primary-500',
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
  );
});
