import { ShieldAlert } from 'lucide-react';

import { KRDS_FOCUS } from '@/lib/focusRing';

import { invalidPromptFieldLabels } from '../promptValidation';
import {
  AUGMENT_PROMPT_FIELD_KEYS,
  AUGMENT_PROMPT_FIELD_META,
  AUGMENT_PROMPT_MAX_LENGTH,
  type AugmentPromptFieldKey,
  type AugmentPromptFields,
} from '../types';

export interface AugmentPromptFieldsetProps {
  /** 입력 원문 5필드 */
  value: AugmentPromptFields;
  /** 필드별 오류 (validateAugmentPrompt 결과) */
  errors: Partial<Record<AugmentPromptFieldKey, string>>;
  /** 사용자가 건드린 필드 — 건드리기 전에는 오류를 띄우지 않는다 */
  touched: Partial<Record<AugmentPromptFieldKey, boolean>>;
  onChange: (key: AugmentPromptFieldKey, value: string) => void;
  onBlur: (key: AugmentPromptFieldKey) => void;
  disabled?: boolean;
}

/**
 * 증강 생성 조건(프롬프트) 입력 — SCR-AUG-001 의 외부 위탁 증강(WINTER/NIGHT/RAIN) 전용 블록.
 *
 * 계약(「생성형 AI API 연동명세서 v1.1」 §4.1):
 * - 5필드(시간대/계절/날씨/지형/심각도) **전부 필수**.
 * - 값은 **자유 문자열** — 명세가 허용값 enum 을 정의하지 않으므로 select 로 가두지 않는다.
 *   placeholder·힌트의 예시는 규격서 샘플일 뿐 선택지가 아니다.
 *
 * 검증은 BE 와 같은 기준(`validateAugmentPrompt`)으로 **미리** 수행해 사용자가 400 을 보지 않게 한다.
 *
 * 접근성(WCAG 2.1 AA):
 * - 모든 입력에 `label htmlFor` 연결, 오류 시 `aria-invalid` + `aria-describedby` 로 사유 연결.
 * - 요약 안내만 `role="alert"` 로 알린다(필드마다 alert 를 두면 낭독이 중복된다).
 *
 * 개인정보: 입력값은 **외부 생성형 AI 로 그대로 전송**되므로 경고를 상시 노출한다.
 */
export function AugmentPromptFieldset({
  value,
  errors,
  touched,
  onChange,
  onBlur,
  disabled,
}: AugmentPromptFieldsetProps) {
  const visibleErrorLabels = invalidPromptFieldLabels(
    Object.fromEntries(
      AUGMENT_PROMPT_FIELD_KEYS.filter((k) => touched[k] && errors[k]).map((k) => [
        k,
        errors[k] as string,
      ]),
    ),
  );

  return (
    <fieldset
      className="space-y-3 rounded-lg border border-gray-200 bg-white p-4 shadow-sm"
      data-testid="augment-prompt-block"
      disabled={disabled}
    >
      <legend className="px-1 text-label font-medium text-gray-500">
        생성 조건 (5개 항목 모두 필수)
      </legend>

      {/* 개인정보 안내 — 입력값이 외부로 나간다는 사실을 입력 지점에서 알린다. */}
      <p className="flex items-start gap-2 rounded-md border border-warning/30 bg-warning/10 px-3 py-2 text-caption text-warning-700">
        <ShieldAlert size={14} className="mt-0.5 shrink-0" aria-hidden />
        <span>
          개인식별정보(이름·차량번호·연락처 등)를 입력하지 마세요 — 입력한 내용은
          외부 생성형 AI 서비스로 그대로 전송됩니다.
        </span>
      </p>

      <div className="grid grid-cols-1 gap-3 md:grid-cols-2 xl:grid-cols-3">
        {AUGMENT_PROMPT_FIELD_KEYS.map((key) => {
          const meta = AUGMENT_PROMPT_FIELD_META[key];
          const error = touched[key] ? errors[key] : undefined;
          const inputId = `aug-prompt-${key}`;
          const describedBy = error ? `${inputId}-error` : `${inputId}-hint`;
          return (
            <div key={key} className="flex flex-col gap-1">
              <label
                htmlFor={inputId}
                className="text-label font-medium text-gray-600"
              >
                {meta.label}
                <span className="ml-0.5 text-danger" aria-hidden>
                  *
                </span>
                <span className="sr-only">(필수)</span>
              </label>
              <input
                id={inputId}
                type="text"
                value={value[key]}
                onChange={(e) => onChange(key, e.target.value)}
                onBlur={() => onBlur(key)}
                placeholder={meta.placeholder}
                maxLength={AUGMENT_PROMPT_MAX_LENGTH}
                autoComplete="off"
                required
                aria-required="true"
                aria-invalid={error ? true : undefined}
                aria-describedby={describedBy}
                className={`rounded-md border bg-white px-2.5 py-1.5 text-body-md ${KRDS_FOCUS} ${
                  error ? 'border-danger' : 'border-gray-300'
                }`}
              />
              {error ? (
                <p id={`${inputId}-error`} className="text-caption text-danger">
                  {error}
                </p>
              ) : (
                <p id={`${inputId}-hint`} className="text-caption text-gray-400">
                  {meta.hint}
                </p>
              )}
            </div>
          );
        })}
      </div>

      {visibleErrorLabels.length > 0 && (
        <p role="alert" className="text-caption text-danger">
          입력을 확인하세요: {visibleErrorLabels.join(', ')} — 5개 항목을 모두
          채워야 요청할 수 있습니다.
        </p>
      )}

      <p className="text-caption text-gray-400">
        정해진 선택지가 아니라 자유 입력입니다. 예시는 참고용이며 항목마다{' '}
        {AUGMENT_PROMPT_MAX_LENGTH}자까지 입력할 수 있습니다. 증강 종류(겨울/야간/우천)는
        위에서 고른 값이 그대로 사용됩니다.
      </p>
    </fieldset>
  );
}
