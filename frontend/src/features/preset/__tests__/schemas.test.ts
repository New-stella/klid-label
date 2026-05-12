import { describe, expect, it } from 'vitest';

import { presetSchema } from '@/features/preset/schemas';

describe('preset schemas', () => {
  it('라벨_코드_1개_이상_요구', () => {
    const empty = {
      name: '테스트 프리셋',
      description: '',
      labelCodes: [],
    };
    const result = presetSchema.safeParse(empty);
    expect(result.success).toBe(false);
    if (!result.success) {
      expect(result.error.issues.some((iss) => /1개 이상/.test(iss.message))).toBe(true);
    }
  });

  it('라벨_코드_최대_20개_제한', () => {
    const tooMany = {
      name: '테스트',
      description: '',
      labelCodes: Array.from({ length: 21 }, (_, i) => `LABEL_${i}`),
    };
    const result = presetSchema.safeParse(tooMany);
    expect(result.success).toBe(false);
    if (!result.success) {
      expect(result.error.issues.some((iss) => /최대 20/.test(iss.message))).toBe(true);
    }
  });

  it('정상_프리셋_허용', () => {
    const ok = {
      name: '교통사고 표준 프리셋',
      description: '교통사고 라벨링용',
      labelCodes: ['PERSON', 'VEHICLE'],
    };
    expect(presetSchema.safeParse(ok).success).toBe(true);
  });

  it('이름_빈_문자열_거부', () => {
    const bad = {
      name: '',
      description: '',
      labelCodes: ['PERSON'],
    };
    expect(presetSchema.safeParse(bad).success).toBe(false);
  });

  it('이름_64자_초과_거부', () => {
    const bad = {
      name: 'a'.repeat(65),
      description: '',
      labelCodes: ['PERSON'],
    };
    expect(presetSchema.safeParse(bad).success).toBe(false);
  });

  it('설명_optional_허용', () => {
    const ok = {
      name: '프리셋',
      labelCodes: ['PERSON'],
    };
    expect(presetSchema.safeParse(ok).success).toBe(true);
  });

  it('라벨_코드_특수문자_거부', () => {
    const bad = {
      name: '프리셋',
      description: '',
      labelCodes: ['<script>'],
    };
    expect(presetSchema.safeParse(bad).success).toBe(false);
  });
});
