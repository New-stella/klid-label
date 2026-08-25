import { z } from 'zod';

/**
 * 프리셋 zod 스키마 (Critical 보안 — 입력 검증).
 *
 * 프리셋은 <b>이벤트유형 + 라벨</b> 둘로만 이루어진다.
 * - eventTypeCd: 필수. 등록된 이벤트유형코드. 1~20자, 영문/숫자/밑줄.
 * - labelIds: 라벨 마스터 PK 배열, 각 항목 양의 정수, 1~20개 (형태는 마스터 소유 → 미전송)
 *
 * 이름·설명은 없앴다(V17) — 이벤트 1건에 프리셋 1건이라 이름은 이벤트명의 중복이었고
 * 설명은 읽는 화면이 없었다. 사람이 읽는 이름은 서버가 실어 주는 이벤트 표시명이 담당한다.
 *
 * 등록된 유형인지의 <b>최종 판정은 BE</b> 다(미등록이면 400) — FE 는 길이·형식만 가드한다.
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
export const EVENT_TYPE_CD_MAX_LENGTH = 20;

/** 라벨 선택 개수 상한 — 편집 모달의 선택 개수 표기('N / 20개 선택')가 이 값을 참조한다. */
export const LABEL_IDS_MAX_COUNT = 20;

// 코드 형식 가드 (UX 1차 — 영문/숫자/밑줄). 등록 여부의 최종 판정은 BE.
const EVENT_TYPE_CD_REGEX = new RegExp(`^[A-Za-z0-9_]{1,${EVENT_TYPE_CD_MAX_LENGTH}}$`);

/** 프리셋 폼 스키마 — 이벤트유형 필수 + labelIds 1~20개. */
export const presetSchema = z.object({
  // ★빈 문자열은 더 이상 '미매핑' 이 아니라 <미입력> 이다. 이벤트에 걸리지 않은 프리셋은
  //   어느 영상에도 매칭되지 않는 죽은 행이라 저장 자체를 막는다.
  eventTypeCd: z
    .string()
    .min(1, '이벤트유형을 선택하세요')
    .max(EVENT_TYPE_CD_MAX_LENGTH, `${EVENT_TYPE_CD_MAX_LENGTH}자 이하`)
    .refine((v) => EVENT_TYPE_CD_REGEX.test(v), {
      message: '지원하지 않는 이벤트 타입입니다',
    }),
  labelIds: z
    .array(z.number().int('labelId 는 정수여야 합니다').positive('labelId 는 양수여야 합니다'))
    .min(1, '라벨을 1개 이상 선택하세요')
    .max(LABEL_IDS_MAX_COUNT, `최대 ${LABEL_IDS_MAX_COUNT}개`),
});

/** 폼 입력/출력 타입 — react-hook-form / onSubmit 공용. */
export type PresetFormValues = z.infer<typeof presetSchema>;
