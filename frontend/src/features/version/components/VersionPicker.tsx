import { Select, type SelectOption } from '@/components/common/Select';

import type { Version } from '../types';

interface VersionPickerProps {
  label: string;
  value: string;
  onChange: (commitSha: string) => void;
  versions: Version[];
  testId?: string;
}

/**
 * diff 비교용 버전 선택 드롭다운.
 * value/onChange는 commitSha 기준 (FE는 단순 전달, BE에서 hex 검증).
 */
export function VersionPicker({ label, value, onChange, versions, testId }: VersionPickerProps) {
  const options: SelectOption[] = versions.map((v) => ({
    value: v.commitSha,
    label: `${v.shortHash} — ${v.message}${v.isCurrent ? ' (현재)' : ''}`,
  }));

  return (
    <Select
      label={label}
      value={value}
      onChange={(e) => onChange(e.target.value)}
      options={options}
      placeholder="선택"
      data-testid={testId}
    />
  );
}
