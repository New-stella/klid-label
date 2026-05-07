import { Select } from '@/components/common/Select';
import { cn } from '@/lib/cn';

import type { EnvMeta } from '../types';

export interface EnvMetaFormProps {
  value: EnvMeta;
  onChange: (next: EnvMeta) => void;
  disabled?: boolean;
  className?: string;
}

const WEATHER_OPTIONS = [
  { value: '', label: '미지정' },
  { value: 'CLEAR', label: '맑음' },
  { value: 'CLOUDY', label: '흐림' },
  { value: 'RAIN', label: '비' },
  { value: 'SNOW', label: '눈' },
  { value: 'FOG', label: '안개' },
];

const TIME_OF_DAY_OPTIONS = [
  { value: '', label: '미지정' },
  { value: 'DAWN', label: '새벽' },
  { value: 'DAY', label: '주간' },
  { value: 'DUSK', label: '저녁' },
  { value: 'NIGHT', label: '야간' },
];

const ILLUMINATION_OPTIONS = [
  { value: '', label: '미지정' },
  { value: 'LOW', label: '낮음' },
  { value: 'MID', label: '중간' },
  { value: 'HIGH', label: '높음' },
];

/**
 * SCR-AUTO-002 외부 시계열 메타 — 환경 메타 검토·수정 폼.
 * V1.7: 외부 시스템이 자동 추출한 환경 정보를 검토·수정만 수행. 자동 생성 X.
 */
export function EnvMetaForm({ value, onChange, disabled, className }: EnvMetaFormProps) {
  return (
    <fieldset
      data-testid="env-meta-form"
      aria-label="환경 메타 (외부 자동 생성)"
      className={cn('rounded border border-border bg-white p-4', className)}
      disabled={disabled}
    >
      <legend className="px-1 text-section-title text-primary">환경 메타</legend>
      <p className="mb-3 text-sub text-neutral">
        외부 시스템이 자동 추출한 환경 정보입니다. 검토 후 수정할 수 있습니다.
      </p>
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
        <Select
          label="날씨"
          value={value.weather ?? ''}
          options={WEATHER_OPTIONS}
          onChange={(e) =>
            onChange({ ...value, weather: (e.target.value || null) as EnvMeta['weather'] })
          }
        />
        <Select
          label="시간대"
          value={value.timeOfDay ?? ''}
          options={TIME_OF_DAY_OPTIONS}
          onChange={(e) =>
            onChange({
              ...value,
              timeOfDay: (e.target.value || null) as EnvMeta['timeOfDay'],
            })
          }
        />
        <Select
          label="조도"
          value={value.illumination ?? ''}
          options={ILLUMINATION_OPTIONS}
          onChange={(e) =>
            onChange({
              ...value,
              illumination: (e.target.value || null) as EnvMeta['illumination'],
            })
          }
        />
      </div>
    </fieldset>
  );
}
