import { normalizeVisibleText } from '@/lib/visibleText';

import {
  AUGMENT_PROMPT_FIELD_KEYS,
  AUGMENT_PROMPT_FIELD_META,
  AUGMENT_PROMPT_MAX_LENGTH,
  type AugmentPromptFieldKey,
  type AugmentPromptFields,
} from './types';

/**
 * 증강 생성 조건(프롬프트) 5필드 검증 — **BE 검증과 같은 기준**으로 미리 막는다.
 *
 * BE 는 두 단계로 거른다:
 * 1. DTO — `@NotBlank` + `@Size(max=50)` (원문 기준)
 * 2. Service — `VisibleTextNormalizer` 정규화 후 재확인(빈 값·길이). 보이지 않는 문자만 채운 값은
 *    `@NotBlank`(trim 기준)를 통과하지만 여기서 걸러진다.
 *
 * FE 가 같은 기준을 갖지 않으면 사용자는 **막을 수 있었던 400** 을 보게 된다. 그래서 원문 길이와
 * 정규화 결과를 모두 확인하고, 전송값도 **정규화된 값**으로 보낸다(전송본 = BE 가 저장·중계할 값).
 */
export interface AugmentPromptValidation {
  /** 5필드 모두 유효한가 */
  ok: boolean;
  /** 필드별 오류 메시지 (유효한 필드는 키 자체가 없음) */
  errors: Partial<Record<AugmentPromptFieldKey, string>>;
  /** 유효할 때만 채워지는 **정규화된** 전송값 */
  value: AugmentPromptFields | null;
}

const REQUIRED_MESSAGE =
  '필수 입력입니다. 공백·보이지 않는 문자만으로는 입력할 수 없습니다.';
const TOO_LONG_MESSAGE = `${AUGMENT_PROMPT_MAX_LENGTH}자 이내로 입력하세요.`;

/**
 * 프롬프트 초안을 검증하고 전송 가능한 정규화 값을 만든다.
 *
 * @param draft 입력 폼의 원문 값 (사용자가 친 그대로)
 */
export function validateAugmentPrompt(
  draft: AugmentPromptFields,
): AugmentPromptValidation {
  const errors: Partial<Record<AugmentPromptFieldKey, string>> = {};
  const normalized: Partial<AugmentPromptFields> = {};

  AUGMENT_PROMPT_FIELD_KEYS.forEach((key) => {
    const raw = draft[key] ?? '';
    // 원문 길이 — BE DTO `@Size(max=50)` 은 정규화 이전 원문에 걸린다.
    if (raw.length > AUGMENT_PROMPT_MAX_LENGTH) {
      errors[key] = TOO_LONG_MESSAGE;
      return;
    }
    const value = normalizeVisibleText(raw);
    if (value === null) {
      errors[key] = REQUIRED_MESSAGE;
      return;
    }
    // 정규화 후 길이 — BE Service 가 같은 값으로 fail-closed 재확인한다.
    if (value.length > AUGMENT_PROMPT_MAX_LENGTH) {
      errors[key] = TOO_LONG_MESSAGE;
      return;
    }
    normalized[key] = value;
  });

  const ok = Object.keys(errors).length === 0;
  return {
    ok,
    errors,
    value: ok ? (normalized as AugmentPromptFields) : null,
  };
}

/** 오류가 있는 필드의 라벨 목록 — 요약 안내용(입력값은 절대 노출하지 않는다, CWE-359). */
export function invalidPromptFieldLabels(
  errors: Partial<Record<AugmentPromptFieldKey, string>>,
): string[] {
  return AUGMENT_PROMPT_FIELD_KEYS.filter((key) => errors[key] !== undefined).map(
    (key) => AUGMENT_PROMPT_FIELD_META[key].label,
  );
}
