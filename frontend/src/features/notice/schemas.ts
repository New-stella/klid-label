import { z } from 'zod';

/**
 * 공지 작성/수정 폼 zod 스키마 (Critical 보안 — 입력 검증).
 *
 * BE NoticeCreateRequest/NoticeUpdateRequest 검증과 정합:
 * - title: 필수(@NotBlank), 200자 이하(@Size max=200)
 * - content: 필수(@NotBlank)
 * - pinned: boolean
 */
export const noticeSchema = z.object({
  title: z
    .string()
    .trim()
    .min(1, '제목은 필수입니다')
    .max(200, '제목은 200자 이하여야 합니다'),
  content: z.string().trim().min(1, '내용은 필수입니다'),
  pinned: z.boolean(),
});

// react-hook-form 이 다루는 폼 입력 타입.
export type NoticeFormValues = z.infer<typeof noticeSchema>;
