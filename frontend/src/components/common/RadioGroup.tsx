import { useId, type ReactNode } from 'react';

import { cn } from '@/lib/cn';

import { Radio } from './Radio';

export interface RadioGroupOption {
  value: string;
  label: ReactNode;
  disabled?: boolean;
}

export interface RadioGroupProps {
  name: string;
  label?: string;
  value?: string;
  defaultValue?: string;
  onChange?: (value: string) => void;
  options: RadioGroupOption[];
  orientation?: 'horizontal' | 'vertical';
  error?: string;
  disabled?: boolean;
}

export function RadioGroup({
  name,
  label,
  value,
  defaultValue,
  onChange,
  options,
  orientation = 'horizontal',
  error,
  disabled,
}: RadioGroupProps) {
  const groupId = useId();
  const errorId = error ? `${groupId}-error` : undefined;

  return (
    <div
      role="radiogroup"
      aria-label={label}
      aria-describedby={errorId}
      aria-invalid={error ? true : undefined}
      className="flex flex-col gap-1"
    >
      {label && <span className="text-body font-medium text-gray-700">{label}</span>}
      <div
        className={cn(
          'flex gap-4',
          orientation === 'vertical' && 'flex-col gap-2',
        )}
      >
        {options.map((opt) => (
          <Radio
            key={opt.value}
            name={name}
            value={opt.value}
            label={opt.label}
            disabled={disabled || opt.disabled}
            checked={value === undefined ? undefined : value === opt.value}
            defaultChecked={value === undefined ? defaultValue === opt.value : undefined}
            onChange={(e) => onChange?.(e.target.value)}
          />
        ))}
      </div>
      {error && (
        <span id={errorId} role="alert" className="text-sub text-danger">
          {error}
        </span>
      )}
    </div>
  );
}
