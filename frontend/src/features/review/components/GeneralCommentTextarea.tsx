import { Field, FieldLabel } from '@/components/common/Field';
import { Textarea } from '@/components/common/Textarea';

export interface GeneralCommentTextareaProps {
  value: string;
  onChange(value: string): void;
  disabled?: boolean;
}

/**
 * 검수 전체 의견 textarea (UI/UX §4-9).
 *
 * 보안: maxLength 1000으로 입력 제한 (BE도 동일 검증).
 */
export function GeneralCommentTextarea({ value, onChange, disabled }: GeneralCommentTextareaProps) {
  return (
    <Field>
      <FieldLabel>전체 의견</FieldLabel>
      <Textarea
        className="min-h-[100px]"
        value={value}
        maxLength={1000}
        disabled={disabled}
        onChange={(e) => onChange(e.target.value)}
        placeholder="검수 전반에 대한 의견을 입력하세요 (선택)"
        data-testid="general-comment-textarea"
      />
    </Field>
  );
}
