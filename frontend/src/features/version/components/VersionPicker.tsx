import { Field, FieldLabel } from '@/components/common/Field';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/common/Select';

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
  return (
    <Field>
      <FieldLabel>{label}</FieldLabel>
      <Select value={value} onValueChange={onChange}>
        <SelectTrigger data-testid={testId}>
          <SelectValue placeholder="선택" />
        </SelectTrigger>
        <SelectContent>
          {versions.map((v) => (
            <SelectItem key={v.commitSha} value={v.commitSha}>
              {v.shortHash} — {v.message}
              {v.isCurrent ? ' (현재)' : ''}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
    </Field>
  );
}
