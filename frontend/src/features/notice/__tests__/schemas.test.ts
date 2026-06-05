import { describe, expect, it } from 'vitest';

import { noticeSchema } from '@/features/notice/schemas';

describe('notice schemas', () => {
  it('제목_미입력시_검증오류', () => {
    const result = noticeSchema.safeParse({ title: '', content: '내용', pinned: false });
    expect(result.success).toBe(false);
    if (!result.success) {
      expect(result.error.issues.some((iss) => /제목은 필수/.test(iss.message))).toBe(
        true,
      );
    }
  });

  it('제목_공백만_입력시_검증오류', () => {
    const result = noticeSchema.safeParse({
      title: '   ',
      content: '내용',
      pinned: false,
    });
    expect(result.success).toBe(false);
  });

  it('제목_200자_초과시_검증오류', () => {
    const result = noticeSchema.safeParse({
      title: 'a'.repeat(201),
      content: '내용',
      pinned: false,
    });
    expect(result.success).toBe(false);
    if (!result.success) {
      expect(result.error.issues.some((iss) => /200자/.test(iss.message))).toBe(true);
    }
  });

  it('내용_미입력시_검증오류', () => {
    const result = noticeSchema.safeParse({ title: '제목', content: '', pinned: false });
    expect(result.success).toBe(false);
    if (!result.success) {
      expect(result.error.issues.some((iss) => /내용은 필수/.test(iss.message))).toBe(
        true,
      );
    }
  });

  it('정상_공지_허용', () => {
    const result = noticeSchema.safeParse({
      title: '시스템 점검 안내',
      content: '2026-06-10 02:00 ~ 04:00 점검 예정입니다.',
      pinned: true,
    });
    expect(result.success).toBe(true);
  });
});
