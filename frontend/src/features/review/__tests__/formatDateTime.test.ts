// formatDateTime — Invalid Date 가드 단위 테스트 (code-reviewer #4).
import { describe, expect, it } from 'vitest';

import { formatDateTime } from '../formatDateTime';

describe('formatDateTime', () => {
  it('정상_ISO_문자열은_ko-KR로_포맷', () => {
    const out = formatDateTime('2026-05-07T10:00:00Z');
    expect(out).not.toBe('');
    expect(out).not.toContain('Invalid');
  });

  it('파싱_불가_문자열은_Invalid_Date_대신_원문_폴백', () => {
    expect(formatDateTime('not-a-date')).toBe('not-a-date');
  });

  it('빈값_null_undefined는_빈_문자열', () => {
    expect(formatDateTime('')).toBe('');
    expect(formatDateTime(null)).toBe('');
    expect(formatDateTime(undefined)).toBe('');
  });
});
