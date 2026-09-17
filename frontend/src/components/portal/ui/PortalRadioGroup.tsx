/**
 * 포털 채널 라디오 그룹 — 관제 공통 라디오 그룹과 같은 props 에 포털 라디오를 쓴다.
 *
 * 왜 따로 있나는 `PortalRadio` 머리말에 있다(Host 스타일이 네이티브 라디오를 숨긴다).
 *
 * `Field` 안에 두면 그 라벨이 그룹 이름이 되고 오류·설명이 자동으로 이어진다 — 공통 필드 문맥을
 * **읽기만** 한다(관제 부품을 고치지 않는다). `Field` 밖이면 호출부가 `aria-label` 을 준다.
 *
 * @design DS-002
 * @design SCREEN-029
 */

import { cn } from '@/lib/cn';
import { useFieldContext } from '@/components/common/fieldContext';
import type { RadioGroupOption, RadioGroupProps } from '@/components/common/RadioGroup';

import { PortalRadio } from './PortalRadio';

export type PortalRadioGroupOption = RadioGroupOption;
export type PortalRadioGroupProps = RadioGroupProps;

export function PortalRadioGroup({
  name,
  value,
  defaultValue,
  onChange,
  options,
  orientation = 'horizontal',
  disabled,
  'aria-label': ariaLabel,
}: PortalRadioGroupProps) {
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
        <PortalRadio
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
