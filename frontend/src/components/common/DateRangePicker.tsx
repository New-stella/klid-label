import { useId } from 'react';

import { cn } from '@/lib/cn';

import { DatePicker } from './DatePicker';
import { Field, FieldLabel } from './Field';

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
  /**
   * 두 `DatePicker` 의 한국어 병기 표시 여부를 함께 정한다(기본 표시).
   *
   * 한 줄 필터 바처럼 값이 들어올 때 블록 높이가 자라면 같은 줄의 다른 입력·버튼과 밑선이
   * 어긋나는 배치에서 끈다. 병기는 입력값을 다시 말해 주는 보조 표시라, 꺼도 값·라벨·상호
   * 제약 등 계약은 그대로다.
   */
  showLocalizedDisplay?: boolean;
}

/**
 * 시작일~종료일 한 쌍 — 두 `DatePicker` 를 묶는 **복합 컴포넌트**다.
 *
 * 내부의 시작일/종료일 라벨은 이 컴포넌트가 소유하는 구조의 일부라 `Field` 조립으로 직접
 * 구성한다(입력 프리미티브가 라벨을 내장하는 것과 다르다). 바깥 `label`/`error` 는 이 한 쌍
 * 전체를 가리키는 그룹 라벨이다.
 */
export function DateRangePicker({
  label,
  value,
  onChange,
  error,
  fromLabel = '시작일',
  toLabel = '종료일',
  className,
  showLocalizedDisplay = true,
}: DateRangePickerProps) {
  const groupId = useId();
  const errorId = error ? `${groupId}-error` : undefined;

  return (
    <div className={cn('flex flex-col gap-2', className)}>
      {label && (
        <span id={groupId} className="text-body font-medium text-gray-700">
          {label}
        </span>
      )}
      <div
        role="group"
        aria-labelledby={label ? groupId : undefined}
        aria-describedby={errorId}
        className="flex items-end gap-2"
      >
        <Field>
          <FieldLabel>{fromLabel}</FieldLabel>
          <DatePicker
            value={value?.from}
            max={value?.to}
            showLocalizedDisplay={showLocalizedDisplay}
            onChange={(from) => onChange?.({ from, to: value?.to })}
          />
        </Field>
        <span aria-hidden="true" className="pb-3 text-gray-600">
          ~
        </span>
        <Field>
          <FieldLabel>{toLabel}</FieldLabel>
          <DatePicker
            value={value?.to}
            min={value?.from}
            showLocalizedDisplay={showLocalizedDisplay}
            onChange={(to) => onChange?.({ from: value?.from, to })}
          />
        </Field>
      </div>
      {error && (
        <span id={errorId} role="alert" className="text-sub text-danger-700">
          {error}
        </span>
      )}
    </div>
  );
}
