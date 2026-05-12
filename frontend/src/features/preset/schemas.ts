import { z } from 'zod';

/**
 * 프리셋 zod 스키마 (Critical 보안 — 입력 검증).
 *
 * - 이름: 1~64자
 * - 설명: 0~500자 (optional)
 * - 라벨 코드: 영문/숫자/_ 1~32자, 1~20개
 */

const LABEL_CODE_REGEX = /^[A-Za-z0-9_]{1,32}$/;

export const presetSchema = z.object({
  name: z.string().min(1, '이름은 필수').max(64, '64자 이하'),
  description: z.string().max(500, '500자 이하').optional(),
  labelCodes: z
    .array(z.string().min(1).max(32).regex(LABEL_CODE_REGEX, '영문/숫자/_ 만 허용'))
    .min(1, '라벨 코드를 1개 이상 추가하세요')
    .max(20, '최대 20개'),
});

export type PresetFormValues = z.infer<typeof presetSchema>;
