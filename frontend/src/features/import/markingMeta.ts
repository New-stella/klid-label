// 일괄 공통 정보의 **형식** 판정 — 빠른 피드백용이다.
//
// ★★이것은 서버 판정을 대신하지 않는다. 서버가 요청 입구(`MarkingImportCreateRequest`)와 적재
//   직전 두 겹으로 같은 규칙을 다시 본다. 화면이 먼저 걸러 주는 이유는 다 고르고 난 뒤에야
//   거부되는 동선을 만들지 않기 위해서다(SCREEN-039).
//
// ★형식만 본다 — 그 값이 실제로 등록된 이벤트 유형인지 같은 존재 판정은 서버가 소유한다.
//
// @design SCREEN-039
// @design API-217

import { MarkingPrivacyType, type MarkingImportMeta } from './markingTypes';

/** 이벤트 유형 코드 — `EV` + 숫자 8자리. */
export const EVENT_TYPE_CD_PATTERN = /^EV[0-9]{8}$/;
/** 지자체 코드 — 숫자 1~10자리. */
export const LOCAL_GOV_CD_PATTERN = /^[0-9]{1,10}$/;
/** 카메라 식별자 — 영문·숫자·밑줄·붙임표 1~64자. */
export const CCTV_ID_PATTERN = /^[A-Za-z0-9_-]{1,64}$/;

/** 화면이 들고 있는 입력값 — 아직 비어 있을 수 있어 전부 문자열이다. */
export interface MarkingMetaDraft {
  eventTypeCd: string;
  localGovCd: string;
  cctvId: string;
  prvcTypeCd: string;
  /** `datetime-local` 값(예: 2026-01-01T02:51). 비우면 싣지 않는다. */
  capturedAt: string;
}

export const EMPTY_MARKING_META: MarkingMetaDraft = {
  eventTypeCd: '',
  localGovCd: '',
  cctvId: '',
  prvcTypeCd: '',
  capturedAt: '',
};

/** 항목별 오류 문구 — 값이 비었거나 형식이 어긋난 자리만 담긴다. */
export type MarkingMetaErrors = Partial<Record<keyof MarkingMetaDraft, string>>;

const PRIVACY_VALUES: readonly string[] = Object.values(MarkingPrivacyType);

/**
 * 공통 정보를 검사해 자리별 문구를 돌려준다.
 *
 * 비어 있는 자리는 「아직 안 채웠다」이지 「틀렸다」가 아니므로 문구를 따로 가른다 — 두 경우에
 * 같은 말을 하면 사람이 무엇을 해야 하는지 알 수 없다.
 */
export function validateMarkingMeta(draft: MarkingMetaDraft): MarkingMetaErrors {
  const errors: MarkingMetaErrors = {};

  if (!draft.eventTypeCd) errors.eventTypeCd = '이벤트 유형을 고르세요.';
  else if (!EVENT_TYPE_CD_PATTERN.test(draft.eventTypeCd))
    errors.eventTypeCd = '이벤트 유형 코드 형식이 올바르지 않습니다.';

  if (!draft.localGovCd) errors.localGovCd = '지자체 코드를 입력하세요.';
  else if (!LOCAL_GOV_CD_PATTERN.test(draft.localGovCd))
    errors.localGovCd = '지자체 코드는 숫자 10자리까지 입력합니다.';

  if (!draft.cctvId) errors.cctvId = 'CCTV ID 를 입력하세요.';
  else if (!CCTV_ID_PATTERN.test(draft.cctvId))
    errors.cctvId = 'CCTV ID 는 영문·숫자·밑줄·붙임표 64자까지 입력합니다.';

  if (!draft.prvcTypeCd) errors.prvcTypeCd = '개인정보 유형을 고르세요.';
  else if (!PRIVACY_VALUES.includes(draft.prvcTypeCd))
    errors.prvcTypeCd = '개인정보 유형이 올바르지 않습니다.';

  return errors;
}

/** 공통 정보가 다 채워지고 형식도 맞는지 — 적재 버튼의 활성 조건 가운데 하나다. */
export function isMarkingMetaReady(draft: MarkingMetaDraft): boolean {
  return Object.keys(validateMarkingMeta(draft)).length === 0;
}

/**
 * 요청 본문에 실을 모양으로 바꾼다.
 *
 * ★촬영 시각은 비어 있으면 **키 자체를 싣지 않는다** — 빈 문자열을 실으면 「주지 않았다」가
 * 아니라 「이 값이다」로 읽혀 서버가 형식 오류로 되돌린다.
 */
export function toMarkingMetaPayload(draft: MarkingMetaDraft): MarkingImportMeta {
  return {
    eventTypeCd: draft.eventTypeCd,
    localGovCd: draft.localGovCd,
    cctvId: draft.cctvId,
    prvcTypeCd: draft.prvcTypeCd as MarkingImportMeta['prvcTypeCd'],
    ...(draft.capturedAt ? { capturedAt: draft.capturedAt } : {}),
  };
}
