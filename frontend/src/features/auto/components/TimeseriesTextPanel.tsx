import { Textarea } from '@/components/common/Textarea';
import { cn } from '@/lib/cn';

export interface TimeseriesTextPanelProps {
  vlmText: string;
  onChange: (text: string) => void;
  disabled?: boolean;
  className?: string;
}

const MAX_LENGTH = 5000;

/**
 * SCR-AUTO-002 VLM 시계열 메타 검토/수정 패널.
 *
 * enum 드롭다운 기반 EnvMetaForm/EventMetaForm 을 대체하여
 * 외부 VLM 이 생성한 자연어 시계열 텍스트를 textarea 로 표시하고 편집한다.
 *
 * 보안: React 의 자동 escape 로 XSS 방어. maxLength 로 입력 크기 제한.
 */
export function TimeseriesTextPanel({
  vlmText,
  onChange,
  disabled,
  className,
}: TimeseriesTextPanelProps) {
  return (
    <fieldset
      aria-label="VLM 시계열 메타"
      className={cn('rounded border border-border bg-white p-4', className)}
      disabled={disabled}
    >
      <legend className="px-1 text-section-title text-primary">VLM 시계열 메타</legend>
      <p className="mb-3 text-sub text-neutral">
        외부 VLM 이 자동 생성한 시계열 정보입니다. 검토 후 수정할 수 있습니다.
      </p>
      <Textarea
        aria-label="VLM 시계열 메타 입력"
        rows={8}
        maxLength={MAX_LENGTH}
        value={vlmText}
        onChange={(e) => onChange(e.target.value)}
        hint={`${vlmText.length}/${MAX_LENGTH}`}
      />
    </fieldset>
  );
}
