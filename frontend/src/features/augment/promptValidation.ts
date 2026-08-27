import { normalizeVisibleText } from '@/lib/visibleText';

import {
  AUGMENT_EVENT_TYPES,
  AUGMENT_FLOOD_SUBTYPES,
  AUGMENT_MTDT_FIELD_KEYS,
  AUGMENT_MTDT_FIELD_META,
  AUGMENT_PROMPT_MAX_LENGTH,
  isAugmentMtdtCode,
  type AugmentEventType,
  type AugmentFloodSubtype,
  type AugmentMtdt,
  type AugmentMtdtDraft,
  type AugmentMtdtFieldKey,
} from './types';

/**
 * 증강 요청의 **생성 조건 축 검증** — BE 와 같은 기준으로 미리 막는다.
 *
 * 「생성형 AI API 연동명세서 v1.3」 §4.1 정합. 이 모듈이 판정하는 것은 셋이다:
 * 1. **이벤트 유형**(`evntType`) — 필수. 계약이 지원하는 두 코드뿐이다.
 * 2. **침수 세부 유형**(`evntSubtype`) — 선택이며 침수일 때만 싣는다. 산불과 함께 보내면 BE 400.
 * 3. **생성 조건**(`mtdt`) — 다섯 항목 전부 필수이며 값은 허용 코드다.
 * 4. **자유 지시문**(`prompt`) — 선택. 정규화 후 비면 키를 싣지 않고, 1000자를 넘으면 오류다.
 *
 * ⚠ **구 동작 폐기(2026-08-27)**: 구 버전은 다섯 항목을 **자유 문자열**로 받아 공백·보이지 않는
 * 문자·50자 상한만 검사했다. v1.3 이 허용 코드를 닫으면서 그 검증 축 자체가 사라졌다 — 이제
 * 화면이 드롭다운으로만 고르게 하므로 코드 밖 값은 애초에 만들어지지 않고, 여기서는 그것을
 * **fail-closed 로 한 번 더 확인**한다(배선 실수·직접 조작을 조용히 통과시키지 않는다).
 *
 * `normalizeVisibleText`(BE `VisibleTextNormalizer` 미러)는 이제 **자유 지시문에만** 쓰인다.
 * BE 도 그 값만 정규화하며, 드롭다운 코드에는 보이지 않는 문자가 섞일 자리가 없다.
 *
 * [@design API-060] [@design INT-008]
 */

/** 입력 폼이 들고 있는 초안 — 미선택은 빈 문자열이다. */
export interface AugmentConditionDraft {
  /** 이벤트 유형. 미선택은 `''`. */
  evntType: string;
  /** 침수 세부 유형. 미선택은 `''`. */
  evntSubtype: string;
  /** 생성 조건 다섯 항목. */
  mtdt: AugmentMtdtDraft;
  /** 자유 지시문 원문(사용자가 친 그대로). */
  prompt: string;
}

/** 전송 가능한 값 — `RequestAugmentRequest` 의 생성 조건 축 부분집합. */
export interface AugmentConditionPayload {
  evntType: AugmentEventType;
  evntSubtype?: AugmentFloodSubtype;
  mtdt: AugmentMtdt;
  prompt?: string;
}

export interface AugmentConditionErrors {
  evntType?: string;
  /** 항목별 오류 (유효한 항목은 키 자체가 없음) */
  mtdt: Partial<Record<AugmentMtdtFieldKey, string>>;
  prompt?: string;
}

export interface AugmentConditionValidation {
  /** 전 축이 유효한가 */
  ok: boolean;
  errors: AugmentConditionErrors;
  /** 유효할 때만 채워지는 전송값 */
  value: AugmentConditionPayload | null;
}

const EVENT_TYPE_REQUIRED_MESSAGE = '이벤트 유형을 선택하세요.';
const MTDT_REQUIRED_MESSAGE = '선택하세요.';
const PROMPT_TOO_LONG_MESSAGE = `${AUGMENT_PROMPT_MAX_LENGTH}자 이내로 입력하세요.`;

const isEventType = (value: string): value is AugmentEventType =>
  (AUGMENT_EVENT_TYPES as readonly string[]).includes(value);

const isFloodSubtype = (value: string): value is AugmentFloodSubtype =>
  (AUGMENT_FLOOD_SUBTYPES as readonly string[]).includes(value);

/**
 * 초안을 검증하고 전송 가능한 값을 만든다.
 *
 * @param draft 입력 폼의 원문 값
 */
export function validateAugmentConditions(
  draft: AugmentConditionDraft,
): AugmentConditionValidation {
  const errors: AugmentConditionErrors = { mtdt: {} };

  // 1. 이벤트 유형 — 필수 + 허용 코드.
  const evntType = isEventType(draft.evntType) ? draft.evntType : null;
  if (evntType === null) {
    errors.evntType = EVENT_TYPE_REQUIRED_MESSAGE;
  }

  // 2. 침수 세부 유형 — 침수일 때만 싣는다. 산불이면 고른 값이 있어도 **전송하지 않는다**
  //    (계약에 산불 세부 코드가 없어 함께 보내면 400). 오류가 아니라 조용한 제외가 맞다 —
  //    유형을 바꾸는 순간 화면이 선택칸을 감추므로 사용자에게는 "고른 적 없는 값"이다.
  const evntSubtype =
    evntType === 'FLOOD' && isFloodSubtype(draft.evntSubtype)
      ? draft.evntSubtype
      : undefined;

  // 3. 생성 조건 — 다섯 항목 전부 필수, 값은 허용 코드.
  const mtdt: Partial<Record<AugmentMtdtFieldKey, string>> = {};
  AUGMENT_MTDT_FIELD_KEYS.forEach((key) => {
    const value = draft.mtdt[key] ?? '';
    if (value === '' || !isAugmentMtdtCode(key, value)) {
      errors.mtdt[key] = MTDT_REQUIRED_MESSAGE;
      return;
    }
    mtdt[key] = value;
  });

  // 4. 자유 지시문 — 선택. 원문 길이와 정규화 결과를 모두 확인한다(BE 와 같은 2단 기준).
  let prompt: string | undefined;
  if (draft.prompt.length > AUGMENT_PROMPT_MAX_LENGTH) {
    errors.prompt = PROMPT_TOO_LONG_MESSAGE;
  } else {
    const normalized = normalizeVisibleText(draft.prompt);
    if (normalized === null) {
      // 비었거나 보이지 않는 문자뿐 — 선택 항목이므로 오류가 아니라 미전송이다.
      prompt = undefined;
    } else if (normalized.length > AUGMENT_PROMPT_MAX_LENGTH) {
      errors.prompt = PROMPT_TOO_LONG_MESSAGE;
    } else {
      prompt = normalized;
    }
  }

  const ok =
    evntType !== null &&
    Object.keys(errors.mtdt).length === 0 &&
    errors.prompt === undefined;

  return {
    ok,
    errors,
    value:
      ok && evntType !== null
        ? {
            evntType,
            ...(evntSubtype !== undefined ? { evntSubtype } : {}),
            mtdt: mtdt as AugmentMtdt,
            ...(prompt !== undefined ? { prompt } : {}),
          }
        : null,
  };
}

/** 오류가 있는 생성 조건 항목의 라벨 목록 — 요약 안내용(선택값은 코드라 노출해도 무해하다). */
export function invalidMtdtFieldLabels(
  errors: Partial<Record<AugmentMtdtFieldKey, string>>,
): string[] {
  return AUGMENT_MTDT_FIELD_KEYS.filter((key) => errors[key] !== undefined).map(
    (key) => AUGMENT_MTDT_FIELD_META[key].label,
  );
}

/**
 * 제출 버튼이 비활성인 **사유** 목록 — 무엇이 모자란지 알려 버튼이 왜 안 눌리는지 찾게 한다
 * (사양 SCREEN-022 하단 고정 바의 요청 불가 사유 안내).
 */
export function augmentConditionBlockReasons(
  validation: AugmentConditionValidation,
): string[] {
  const reasons: string[] = [];
  if (validation.errors.evntType) reasons.push('이벤트 유형');
  const missing = invalidMtdtFieldLabels(validation.errors.mtdt);
  if (missing.length > 0) reasons.push(`생성 조건(${missing.join(', ')})`);
  if (validation.errors.prompt) reasons.push('자유 지시문 길이');
  return reasons;
}
