import { type ReactNode } from 'react';

import { cn } from '@/lib/cn';

import { Radio } from './Radio';
import { useFieldContext } from './fieldContext';

export interface RadioGroupOption {
  value: string;
  label: ReactNode;
  disabled?: boolean;
}

/**
 * 라디오 그룹 — 라벨·오류 문구를 갖지 않는다.
 *
 * `Field` 안에 두면 `FieldLabel`/`FieldTitle` 이 그룹 이름이 되고(`aria-labelledby`),
 * `FieldError` 가 오류 안내를 맡는다(`aria-describedby` + `aria-invalid` 자동 연결).
 * Field 밖에서 쓰면 호출부가 `aria-label` 을 직접 지정한다.
 */
export interface RadioGroupProps {
  name: string;
  value?: string;
  defaultValue?: string;
  onChange?: (value: string) => void;
  options: RadioGroupOption[];
  orientation?: 'horizontal' | 'vertical';
  disabled?: boolean;
  'aria-label'?: string;
}

export function RadioGroup({
  name,
  value,
  defaultValue,
  onChange,
  options,
  orientation = 'horizontal',
  disabled,
  'aria-label': ariaLabel,
}: RadioGroupProps) {
  const field = useFieldContext();
  const labelledBy = !ariaLabel && field?.hasLabel ? field.labelId : undefined;
  const describedBy = field?.hasError
    ? field.errorId
    : field?.hasDescription
      ? field.descriptionId
      : undefined;

  return (
    <div
      role="radiogroup"
      aria-label={ariaLabel}
      aria-labelledby={labelledBy}
      aria-describedby={describedBy}
      aria-invalid={field?.hasError ? true : undefined}
      className={cn('flex gap-4', orientation === 'vertical' && 'flex-col gap-2')}
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
  );
}
