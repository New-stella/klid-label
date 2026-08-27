import {
  AUGMENT_MTDT_FIELD_KEYS,
  AUGMENT_MTDT_FIELD_META,
  augmentMtdtCodeLabel,
  type AugmentMtdtFieldKey,
} from '../types';

export interface AugmentPromptSummaryProps {
  /** 결과 항목 id — testid 스코프용 */
  augmentId: number;
  /** BE 가 저장한 전송 원문(JSON 문자열). 해상도 파생·구 요청은 null */
  prompt?: string | null;
}

/** 파싱 결과 — 생성 조건 항목값과 자유 지시문. 어느 것도 못 읽으면 null. */
interface ParsedPrompt {
  mtdt: Partial<Record<AugmentMtdtFieldKey, string>>;
  freeText: string | null;
}

/** 어떤 값이든 표시 가능한 문자열로 좁힌다(문자열·숫자·불리언만 — 객체·배열은 버린다). */
function asDisplayValue(value: unknown): string | null {
  if (typeof value === 'string') return value.trim() === '' ? null : value;
  if (typeof value === 'number' || typeof value === 'boolean') return String(value);
  return null;
}

function collectMtdt(source: Record<string, unknown>): Partial<
  Record<AugmentMtdtFieldKey, string>
> {
  const fields: Partial<Record<AugmentMtdtFieldKey, string>> = {};
  for (const key of AUGMENT_MTDT_FIELD_KEYS) {
    const value = asDisplayValue(source[key]);
    if (value !== null) fields[key] = value;
  }
  return fields;
}

/**
 * 보관 원문을 읽는다 — **두 형태를 모두 견딘다**.
 *
 * - **v1.3 이후**: `{"mtdt":{time,season,...},"prompt":"자유 지시문"}` (자유 지시문이 없으면 키 자체가 없다)
 * - **v1.1 이전 적재분**: `{"time":"야간","season":"겨울",...}` — 값이 한글 자유 문자열이다
 *
 * 옛 형태를 계속 읽어야 하는 이유는 그 값이 **같은 (영상 × 종류) 파생을 구분하는 유일한 축**이라
 * 과거 결과물의 식별 근거이기 때문이다(중복 요청 허용 정책). 코드 표시 문구 매핑은 모르는 값을
 * 그대로 돌려주므로(`augmentMtdtCodeLabel`) 옛 한글 값도 손상 없이 나온다.
 */
function parseStoredPrompt(raw: string): ParsedPrompt | null {
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return null;
  }
  if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
    return null;
  }
  const source = parsed as Record<string, unknown>;

  // v1.3 형태 — mtdt 객체가 있으면 그 안에서 항목을 읽고, prompt 는 자유 지시문(문자열)이다.
  const nested = source.mtdt;
  if (typeof nested === 'object' && nested !== null && !Array.isArray(nested)) {
    return {
      mtdt: collectMtdt(nested as Record<string, unknown>),
      freeText: asDisplayValue(source.prompt),
    };
  }

  // v1.1 형태 — 최상위에 다섯 항목이 평평하게 있고 자유 지시문 축은 존재하지 않았다.
  const flat = collectMtdt(source);
  return Object.keys(flat).length > 0 ? { mtdt: flat, freeText: null } : null;
}

/**
 * 항목별 **생성 조건**(R9 역추적) 표시.
 *
 * 같은 (영상 × 종류) 재요청이 허용되므로 "이 결과물이 어떤 조건으로 만들어졌는가" 가
 * 결과를 구분하는 유일한 단서다. 원문은 서버가 재가공하지 않은 전송 그대로의 JSON 문자열이라
 * **파싱 실패에 안전해야 한다** — 실패하면 원문을 그대로 보여주고 화면을 죽이지 않는다.
 *
 * 보안: 값은 BE 응답 문자열이며 React 가 자동 escape 한다(XSS).
 *
 * [@design SCREEN-023] [@design INT-008]
 */
export function AugmentPromptSummary({ augmentId, prompt }: AugmentPromptSummaryProps) {
  if (!prompt || prompt.trim() === '') return null;

  const parsed = parseStoredPrompt(prompt);
  const shownKeys = parsed
    ? AUGMENT_MTDT_FIELD_KEYS.filter((key) => parsed.mtdt[key] !== undefined)
    : [];

  return (
    <section
      data-testid={`augment-prompt-${augmentId}`}
      aria-label="생성 조건"
      className="rounded border border-border bg-bgLight p-3"
    >
      <h3 className="mb-2 text-sub font-semibold text-gray-600">생성 조건</h3>
      {parsed && (shownKeys.length > 0 || parsed.freeText !== null) ? (
        <>
          {shownKeys.length > 0 && (
            <dl className="grid grid-cols-2 gap-x-4 gap-y-2 md:grid-cols-5">
              {shownKeys.map((key) => (
                <div key={key}>
                  <dt className="text-sub text-gray-600">
                    {AUGMENT_MTDT_FIELD_META[key].label}
                  </dt>
                  <dd className="text-body font-medium text-gray-800 break-words">
                    {augmentMtdtCodeLabel(key, parsed.mtdt[key] as string)}
                  </dd>
                </div>
              ))}
            </dl>
          )}
          {parsed.freeText !== null && (
            <div className="mt-2" data-testid={`augment-free-prompt-${augmentId}`}>
              <h4 className="text-sub text-gray-600">자유 지시문</h4>
              <p className="text-body text-gray-800 whitespace-pre-wrap break-words">
                {parsed.freeText}
              </p>
            </div>
          )}
        </>
      ) : (
        <p className="text-sub text-gray-700 whitespace-pre-wrap break-words">
          {prompt}
        </p>
      )}
    </section>
  );
}
