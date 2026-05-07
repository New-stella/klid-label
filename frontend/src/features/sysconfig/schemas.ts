import { z } from 'zod';

/**
 * 시스템 설정 zod 스키마 (Critical 보안 — 입력 검증).
 *
 * 보안: 사용자 입력은 zod로 1차 검증한 후 BE에 전달.
 * BE는 서버 측 재검증 (이중 방어).
 */

export const ffmpegConfigSchema = z.object({
  FFMPEG_THREADS: z
    .number({ invalid_type_error: '숫자를 입력해주세요' })
    .int('정수만 허용')
    .min(1, '1 ~ 16 범위 내에서 입력해주세요')
    .max(16, '1 ~ 16 범위 내에서 입력해주세요'),
  FFMPEG_OUTPUT_FPS: z
    .number({ invalid_type_error: '숫자를 입력해주세요' })
    .int('정수만 허용')
    .min(1, '1 ~ 30 범위 내에서 입력해주세요')
    .max(30, '1 ~ 30 범위 내에서 입력해주세요'),
});

export type FFmpegConfigForm = z.infer<typeof ffmpegConfigSchema>;

export const batchConfigSchema = z.object({
  BATCH_INTERVAL_SEC: z
    .number({ invalid_type_error: '숫자를 입력해주세요' })
    .int('정수만 허용')
    .min(10, '10 ~ 3600 범위 내에서 입력해주세요')
    .max(3600, '10 ~ 3600 범위 내에서 입력해주세요'),
  BATCH_CONCURRENCY: z
    .number({ invalid_type_error: '숫자를 입력해주세요' })
    .int('정수만 허용')
    .min(1, '1 ~ 10 범위 내에서 입력해주세요')
    .max(10, '1 ~ 10 범위 내에서 입력해주세요'),
});

export type BatchConfigForm = z.infer<typeof batchConfigSchema>;
