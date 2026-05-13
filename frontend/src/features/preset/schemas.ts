import { z } from 'zod';

import { EVENT_TYPE_CODES, type EventTypeCode } from '@/constants/eventTypes';

/**
 * 프리셋 zod 스키마 (Critical 보안 — 입력 검증).
 *
 * - 이름: 1~64자
 * - 설명: 0~500자 (optional)
 * - 라벨 코드: 영문/숫자/_ 1~32자, 1~20개
 * - 이벤트 타입 코드: SoT 6 종(EVT_FALL/EVT_VIOLENCE/EVT_ACCIDENT/EVT_ABNORMAL/EVT_FLOOD/EVT_FIRE)
 *   또는 빈 문자열 (BE PresetRequest 검증과 동일)
 */

const LABEL_CODE_REGEX = /^[A-Za-z0-9_]{1,32}$/;

// SoT 6 종 코드만 허용 + 빈 문자열 (미매핑).
const EVENT_TYPE_CD_ALLOWED: ReadonlySet<string> = new Set<string>([
  ...EVENT_TYPE_CODES,
  '',
]);

export const presetSchema = z.object({
  name: z.string().min(1, '이름은 필수').max(64, '64자 이하'),
  description: z.string().max(500, '500자 이하').optional(),
  labelCodes: z
    .array(z.string().min(1).max(32).regex(LABEL_CODE_REGEX, '영문/숫자/_ 만 허용'))
    .min(1, '라벨 코드를 1개 이상 추가하세요')
    .max(20, '최대 20개'),
  eventTypeCd: z
    .string()
    .max(32, '32자 이하')
    .refine((v) => EVENT_TYPE_CD_ALLOWED.has(v), {
      message: '지원하지 않는 이벤트 타입입니다',
    })
    .optional(),
});

export type PresetFormValues = z.infer<typeof presetSchema>;
export type AllowedEventTypeCd = EventTypeCode | '';
