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

// categoryKey 형식 가드 (UX 1차 — 빈값 허용, 영문/숫자 1~32자). 유효성 최종 판정은 BE.
const EVENT_TYPE_CD_REGEX = /^[A-Za-z0-9_]{1,32}$/;

/** 프리셋 폼 스키마 — labelIds 최소 1개, 최대 20개. */
export const presetSchema = z.object({
  name: z.string().min(1, '이름은 필수').max(64, '64자 이하'),
  description: z.string().max(500, '500자 이하').optional(),
  labelIds: z
    .array(z.number().int('labelId 는 정수여야 합니다').positive('labelId 는 양수여야 합니다'))
    .min(1, '라벨을 1개 이상 선택하세요')
    .max(20, '최대 20개'),
  eventTypeCd: z
    .string()
    .max(32, '32자 이하')
    .refine((v) => v === '' || EVENT_TYPE_CD_REGEX.test(v), {
      message: '지원하지 않는 이벤트 타입입니다',
    })
    .optional(),
});

/** 폼 입력/출력 타입 — react-hook-form / onSubmit 공용. */
export type PresetFormValues = z.infer<typeof presetSchema>;
/** 매핑 이벤트 타입 값 — categoryKey 문자열 또는 빈 문자열(미매핑). */
export type AllowedEventTypeCd = string;
