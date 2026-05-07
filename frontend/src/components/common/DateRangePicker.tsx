import { useId } from 'react';

import { cn } from '@/lib/cn';

import { DatePicker } from './DatePicker';

export interface DateRange {
  from?: string;
  to?: string;
}

export interface DateRangePickerProps {
  label?: string;
  value?: DateRange;
  onChange?: (value: DateRange) => void;
  error?: string;
  fromLabel?: string;
  toLabel?: string;
  className?: string;
}

export function DateRangePicker({
  label,
  value,
  onChange,
  error,
  fromLabel = '시작일',
  toLabel = '종료일',
  className,
}: DateRangePickerProps) {
  const groupId = useId();

  return (
    <div className={cn('flex flex-col gap-2', className)}>
      {label && (
        <span id={groupId} className="text-body font-medium text-primary">
          {label}
        </span>
      )}
      <div role="group" aria-labelledby={label ? groupId : undefined} className="flex items-end gap-2">
        <DatePicker
          label={fromLabel}
          value={value?.from}
          max={value?.to}
          onChange={(from) => onChange?.({ from, to: value?.to })}
        />
        <span aria-hidden="true" className="pb-3 text-neutral">
          ~
        </span>
        <DatePicker
          label={toLabel}
          value={value?.to}
          min={value?.from}
          onChange={(to) => onChange?.({ from: value?.from, to })}
        />
      </div>
      {error && (
        <span role="alert" className="text-sub text-danger">
          {error}
        </span>
      )}
    </div>
  );
}
