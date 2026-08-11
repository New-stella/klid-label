import { z } from 'zod';

/**
 * 프리셋 zod 스키마 (Critical 보안 — 입력 검증).
 *
 * Phase 4 (마스터 연동):
 * - 이름: 1~64자
 * - 설명: 0~500자 (optional)
 * - labelIds: 라벨 마스터 PK 배열, 각 항목 양의 정수, 1~20개 (형태는 마스터 소유 → 미전송)
 * - 이벤트 타입 코드: 관제 카테고리 키(categoryKey) 또는 빈 문자열(미매핑).
 *   유효성 최종 판정은 BE — FE 는 길이·형식만 가드한다.
 */

/**
 * 이벤트 타입 코드 최대 길이.
 *
 * 진실원은 **코드값 표준도메인 VARCHAR(20)** 이고 실제 컬럼 `LS_LABEL_PRESET.EVNT_TYPE_CD` 도
 * 20 이다. BE DTO(`PresetRequest`)가 입구에서 20 초과를 400 으로 거부한다.
 *
 * ⚠ 상한을 넓혀 맞추지 말 것 — FE 가 32 였던 동안 21~32자는 FE 검증을 통과해 전송된 뒤
 *   서버에서 400 으로 되돌아왔다(막을 수 있는 왕복).
 */
const EVENT_TYPE_CD_MAX_LENGTH = 20;

/**
 * 이름·설명 길이 상한.
 *
 * 화면의 도움말 문구와 글자수 카운터가 이 값을 참조한다 — 같은 상한을 스키마와 화면에 각각
 * 적으면 한쪽만 바뀌어 "허용된다고 안내한 길이가 거부되는" 어긋남이 생긴다.
 */
export const NAME_MAX_LENGTH = 64;
export const DESCRIPTION_MAX_LENGTH = 500;

/** 라벨 선택 개수 상한 — 편집 모달의 선택 개수 표기('N / 20개 선택')가 이 값을 참조한다. */
export const LABEL_IDS_MAX_COUNT = 20;

// categoryKey 형식 가드 (UX 1차 — 빈값 허용, 영문/숫자/밑줄). 유효성 최종 판정은 BE.
const EVENT_TYPE_CD_REGEX = new RegExp(`^[A-Za-z0-9_]{1,${EVENT_TYPE_CD_MAX_LENGTH}}$`);

/** 프리셋 폼 스키마 — labelIds 최소 1개, 최대 20개. */
export const presetSchema = z.object({
  name: z.string().min(1, '이름은 필수').max(NAME_MAX_LENGTH, `${NAME_MAX_LENGTH}자 이하`),
  description: z
    .string()
    .max(DESCRIPTION_MAX_LENGTH, `${DESCRIPTION_MAX_LENGTH}자 이하`)
    .optional(),
  labelIds: z
    .array(z.number().int('labelId 는 정수여야 합니다').positive('labelId 는 양수여야 합니다'))
    .min(1, '라벨을 1개 이상 선택하세요')
    .max(LABEL_IDS_MAX_COUNT, `최대 ${LABEL_IDS_MAX_COUNT}개`),
  eventTypeCd: z
    .string()
    .max(EVENT_TYPE_CD_MAX_LENGTH, `${EVENT_TYPE_CD_MAX_LENGTH}자 이하`)
    .refine((v) => v === '' || EVENT_TYPE_CD_REGEX.test(v), {
      message: '지원하지 않는 이벤트 타입입니다',
    })
    .optional(),
});

/** 폼 입력/출력 타입 — react-hook-form / onSubmit 공용. */
export type PresetFormValues = z.infer<typeof presetSchema>;
/** 매핑 이벤트 타입 값 — categoryKey 문자열 또는 빈 문자열(미매핑). */
export type AllowedEventTypeCd = string;
