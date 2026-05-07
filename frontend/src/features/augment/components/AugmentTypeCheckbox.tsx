import { Checkbox } from '@/components/common/Checkbox';

import { type AugmentType } from '../types';

export interface AugmentTypeCheckboxProps {
  type: AugmentType;
  checked: boolean;
  onChange: (next: boolean) => void;
  disabled?: boolean;
}

const labelMap: Record<AugmentType, string> = {
  WINTER: '겨울',
  NIGHT: '야간',
  RAIN: '비',
  RESOLUTION: '해상도',
};

/**
 * 증강 유형 체크박스 (UI/UX §4-12 — 4종 복수 선택).
 */
export function AugmentTypeCheckbox({
  type,
  checked,
  onChange,
  disabled,
}: AugmentTypeCheckboxProps) {
  return (
    <Checkbox
      data-testid={`augment-type-${type}`}
      label={labelMap[type]}
      checked={checked}
      disabled={disabled}
      onChange={(e) => onChange(e.target.checked)}
    />
  );
}
