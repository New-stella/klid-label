import { Input } from '@/components/common/Input';
import { Select } from '@/components/common/Select';
import { Textarea } from '@/components/common/Textarea';
import { cn } from '@/lib/cn';

import type { EventMeta } from '../types';

export interface EventMetaFormProps {
  value: EventMeta;
  onChange: (next: EventMeta) => void;
  disabled?: boolean;
  className?: string;
}

const INTENSITY_OPTIONS = [
  { value: '', label: '미지정' },
  { value: 'LOW', label: '낮음' },
  { value: 'MID', label: '중간' },
  { value: 'HIGH', label: '높음' },
];

const MAX_DESCRIPTION = 500;

/**
 * SCR-AUTO-002 외부 시계열 메타 — 이벤트 메타 검토·수정 폼.
 * V1.7: 외부 시스템 자동 추출 결과의 검토·수정 영역.
 *
 * 보안: description은 length 제한 (XSS는 React 자동 escape).
 */
export function EventMetaForm({ value, onChange, disabled, className }: EventMetaFormProps) {
  return (
    <fieldset
      data-testid="event-meta-form"
      aria-label="이벤트 메타 (외부 자동 생성)"
      className={cn('rounded border border-border bg-white p-4', className)}
      disabled={disabled}
    >
      <legend className="px-1 text-section-title text-primary">이벤트 메타</legend>
      <p className="mb-3 text-sub text-neutral">
        외부 시스템이 자동 추출한 이벤트 정보입니다.
      </p>
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
        <Input
          label="이벤트 코드"
          value={value.eventTypeCd ?? ''}
          maxLength={32}
          onChange={(e) =>
            onChange({ ...value, eventTypeCd: e.target.value || null })
          }
        />
        <Select
          label="강도"
          value={value.intensity ?? ''}
          options={INTENSITY_OPTIONS}
          onChange={(e) =>
            onChange({
              ...value,
              intensity: (e.target.value || null) as EventMeta['intensity'],
            })
          }
        />
      </div>
      <div className="mt-3">
        <Textarea
          label="설명"
          rows={3}
          maxLength={MAX_DESCRIPTION}
          value={value.description ?? ''}
          onChange={(e) =>
            onChange({ ...value, description: e.target.value || null })
          }
          hint={`${(value.description ?? '').length}/${MAX_DESCRIPTION}자`}
        />
      </div>
    </fieldset>
  );
}
