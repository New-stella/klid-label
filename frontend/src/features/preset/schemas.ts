import { z } from 'zod';

/**
 * 프리셋 zod 스키마 (Critical 보안 — 입력 검증).
 *
 * - 이름: 1~50자, 한글/영문/숫자/공백/하이픈/언더스코어만 허용 (특수문자 제한)
 * - 라벨 항목: 최대 6종 (UI/UX §4-4)
 * - 색상: #rrggbb 정규식 검증
 */

const NAME_REGEX = /^[가-힣A-Za-z0-9 _-]{1,50}$/;
const COLOR_REGEX = /^#[0-9a-fA-F]{6}$/;

const LABEL_SHAPE = z.enum(['BBOX', 'POLYGON', 'SEGMENT', 'TRACK']);
const EVENT_TYPE = z.enum([
  'FALL',
  'VIOLENCE',
  'TRAFFIC_ACCIDENT',
  'ABNORMAL_BEHAVIOR',
  'FLOOD',
  'WILDFIRE',
]);

export const labelItemSchema = z.object({
  id: z.number().int().optional(),
  name: z.string().regex(NAME_REGEX, '항목명은 1~50자, 특수문자 제한'),
  shape: LABEL_SHAPE,
  color: z.string().regex(COLOR_REGEX, '#RRGGBB 형식 필수'),
  attributes: z.record(z.string(), z.string()).optional(),
});

export const presetSchema = z.object({
  name: z.string().regex(NAME_REGEX, '프리셋명은 1~50자, 특수문자 제한'),
  eventTypeCd: EVENT_TYPE,
  subType: z.string().max(50).optional(),
  items: z
    .array(labelItemSchema)
    .min(1, '라벨 항목은 1개 이상')
    .max(6, '라벨 항목은 최대 6개까지 등록 가능합니다'),
});

export type PresetForm = z.infer<typeof presetSchema>;
export type LabelItemForm = z.infer<typeof labelItemSchema>;
