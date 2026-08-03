import {
  AUGMENT_PROMPT_FIELD_KEYS,
  AUGMENT_PROMPT_FIELD_META,
  type AugmentPromptFieldKey,
} from '../types';

export interface AugmentPromptSummaryProps {
  /** 결과 항목 id — testid 스코프용 */
  augmentId: number;
  /** BE 가 저장한 전송 원문(JSON 문자열). 해상도 파생·구 요청은 null */
  prompt?: string | null;
}

/** 파싱 결과 — 5필드 값(문자열)만 추린다. 파싱 실패/비객체면 null. */
function parsePromptFields(
  raw: string,
): Partial<Record<AugmentPromptFieldKey, string>> | null {
  try {
    const parsed: unknown = JSON.parse(raw);
    if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
      return null;
    }
    const source = parsed as Record<string, unknown>;
    const fields: Partial<Record<AugmentPromptFieldKey, string>> = {};
    for (const key of AUGMENT_PROMPT_FIELD_KEYS) {
      const value = source[key];
      if (typeof value === 'string' && value.trim() !== '') {
        fields[key] = value;
      } else if (typeof value === 'number' || typeof value === 'boolean') {
        fields[key] = String(value);
      }
    }
    return Object.keys(fields).length > 0 ? fields : null;
  } catch {
    return null;
  }
}

/**
 * 항목별 **생성 조건**(R9 역추적) 표시.
 *
 * 같은 (영상 × 종류) 재요청이 허용되므로 "이 결과물이 어떤 조건으로 만들어졌는가" 가
 * 결과를 구분하는 유일한 단서다. 원문은 서버가 재가공하지 않은 전송 그대로의 JSON 문자열이라
 * **파싱 실패에 안전해야 한다** — 실패하면 원문을 그대로 보여주고 화면을 죽이지 않는다.
 *
 * 보안: 값은 BE 응답 문자열이며 React 가 자동 escape 한다(XSS).
 */
export function AugmentPromptSummary({ augmentId, prompt }: AugmentPromptSummaryProps) {
  if (!prompt || prompt.trim() === '') return null;

  const fields = parsePromptFields(prompt);

  return (
    <section
      data-testid={`augment-prompt-${augmentId}`}
      aria-label="생성 조건"
      className="rounded border border-border bg-bgLight p-3"
    >
      <h3 className="mb-2 text-sub font-semibold text-gray-600">생성 조건</h3>
      {fields ? (
        <dl className="grid grid-cols-2 gap-x-4 gap-y-2 md:grid-cols-5">
          {AUGMENT_PROMPT_FIELD_KEYS.filter((key) => fields[key] !== undefined).map(
            (key) => (
              <div key={key}>
                <dt className="text-sub text-gray-500">
                  {AUGMENT_PROMPT_FIELD_META[key].label}
                </dt>
                <dd className="text-body font-medium text-gray-800 break-words">
                  {fields[key]}
                </dd>
              </div>
            ),
          )}
        </dl>
      ) : (
        <p className="text-sub text-gray-700 whitespace-pre-wrap break-words">
          {prompt}
        </p>
      )}
    </section>
  );
}
