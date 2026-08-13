import { describe, expect, it } from 'vitest';

import { formatFileSize } from '@/lib/formatFileSize';

describe('formatFileSize', () => {
  it('1KB_미만은_바이트로_표기한다', () => {
    expect(formatFileSize(0)).toBe('0 B');
    expect(formatFileSize(1023)).toBe('1023 B');
  });

  it('1KB_이상_1MB_미만은_KB_소수1자리다', () => {
    expect(formatFileSize(1024)).toBe('1.0 KB');
    expect(formatFileSize(348201)).toBe('340.0 KB');
  });

  it('1MB_이상은_MB_소수1자리다', () => {
    expect(formatFileSize(1024 * 1024)).toBe('1.0 MB');
    expect(formatFileSize(5 * 1024 * 1024 + 512 * 1024)).toBe('5.5 MB');
  });

  it('경계값_1024는_KB_로_넘어간다', () => {
    // 경계에서 두 단위가 겹치면 같은 파일이 화면마다 다르게 보인다.
    expect(formatFileSize(1023)).toContain('B');
    expect(formatFileSize(1024)).toContain('KB');
  });
});
