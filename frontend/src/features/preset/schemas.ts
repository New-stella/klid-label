import { z } from 'zod';

/**
 * 프리셋 zod 스키마 (Critical 보안 — 입력 검증).
 *
 * - 이름: 1~64자
 * - 설명: 0~500자 (optional)
 * - 라벨 코드: 영문/숫자/_ 1~32자, 1~20개
 * - 라벨 옵션 (Phase 3): 각 항목 BBOX/POLYGON 중 최소 하나는 true (둘 다 false 거부)
 * - 이벤트 타입 코드: 관제 카테고리 키(categoryKey, 예 "010001") 또는 빈 문자열(미매핑).
 *   유효 카테고리 검증은 서버(BE PresetRequest 4a)에 위임한다 — FE 는 길이·형식만 가드한다.
 */

const LABEL_CODE_REGEX = /^[A-Za-z0-9_]{1,32}$/;

// categoryKey 형식 가드 (UX 1차 — 빈값 허용, 영문/숫자 1~32자). 유효성 최종 판정은 BE.
const EVENT_TYPE_CD_REGEX = /^[A-Za-z0-9_]{1,32}$/;

/** 라벨 코드별 BBOX/POLYGON 옵션 — 둘 다 false 거부. */
export const labelCodeOptionSchema = z
  .object({
    code: z
      .string()
      .min(1, '라벨 코드는 필수')
      .max(32, '32자 이하')
      .regex(LABEL_CODE_REGEX, '영문/숫자/_ 만 허용'),
    bboxEnabled: z.boolean(),
    polygonEnabled: z.boolean(),
  })
  .refine((v) => v.bboxEnabled || v.polygonEnabled, {
    message: 'BBOX 또는 POLYGON 중 최소 하나는 활성화해야 합니다.',
  });

export type LabelCodeOptionInput = z.infer<typeof labelCodeOptionSchema>;

/**
 * 프리셋 폼 스키마.
 *
 * 입력 유연성을 위해 다음 중 한 형식을 허용한다:
 * 1) `labelCodeOptions` 만 (Phase 3 신규)
 * 2) `labelCodes` 만 (레거시) → 각 항목을 {bbox:true, polygon:true} 로 normalize
 *
 * 둘 다 누락 시 `labelCodeOptions` 비어 있다고 판단해 거부.
 */
export const presetSchema = z
  .object({
    name: z.string().min(1, '이름은 필수').max(64, '64자 이하'),
    description: z.string().max(500, '500자 이하').optional(),
    labelCodes: z.array(z.string()).optional(),
    labelCodeOptions: z.array(labelCodeOptionSchema).max(20, '최대 20개').optional(),
    eventTypeCd: z
      .string()
      .max(32, '32자 이하')
      .refine((v) => v === '' || EVENT_TYPE_CD_REGEX.test(v), {
        message: '지원하지 않는 이벤트 타입입니다',
      })
      .optional(),
  })
  .transform((v) => {
    // labelCodeOptions 우선, 없으면 labelCodes → 모두 (true,true) 로 normalize
    const options: LabelCodeOptionInput[] =
      v.labelCodeOptions && v.labelCodeOptions.length > 0
        ? v.labelCodeOptions
        : (v.labelCodes ?? []).map((code) => ({
            code,
            bboxEnabled: true,
            polygonEnabled: true,
          }));
    return {
      ...v,
      labelCodeOptions: options,
      labelCodes: options.map((o) => o.code),
    };
  })
  .superRefine((v, ctx) => {
    if (v.labelCodeOptions.length < 1) {
      ctx.addIssue({
        code: z.ZodIssueCode.custom,
        path: ['labelCodeOptions'],
        message: '라벨 코드를 1개 이상 추가하세요',
      });
    }
    if (v.labelCodeOptions.length > 20) {
      ctx.addIssue({
        code: z.ZodIssueCode.custom,
        path: ['labelCodeOptions'],
        message: '최대 20개',
      });
    }
    // 레거시 labelCodes 만 들어온 경우에도 코드 형식/길이 검증
    for (let i = 0; i < v.labelCodeOptions.length; i += 1) {
      const opt = v.labelCodeOptions[i]!;
      if (!LABEL_CODE_REGEX.test(opt.code)) {
        ctx.addIssue({
          code: z.ZodIssueCode.custom,
          path: ['labelCodeOptions', i, 'code'],
          message: '영문/숫자/_ 만 허용',
        });
      }
    }
  });

// 입력 타입 (transform 전) — react-hook-form 이 다루는 폼 상태.
export type PresetFormInput = z.input<typeof presetSchema>;
// 출력 타입 (transform 후) — onSubmit 핸들러가 받는 정규화된 값.
export type PresetFormOutput = z.output<typeof presetSchema>;
// 레거시 alias.
export type PresetFormValues = PresetFormInput;
/** 매핑 이벤트 타입 값 — categoryKey 문자열 또는 빈 문자열(미매핑). */
export type AllowedEventTypeCd = string;
