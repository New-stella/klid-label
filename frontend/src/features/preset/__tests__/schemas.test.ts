import { describe, expect, it } from 'vitest';

import { presetSchema } from '@/features/preset/schemas';

const baseItem = { name: '항목1', shape: 'BBOX' as const, color: '#ef4444' };

describe('preset schemas', () => {
  it('프리셋_라벨_항목_최대_6종_제한', () => {
    const tooMany = {
      name: '테스트 프리셋',
      eventTypeCd: 'FALL' as const,
      items: Array.from({ length: 7 }, (_, i) => ({ ...baseItem, name: `항목${i + 1}` })),
    };
    const result = presetSchema.safeParse(tooMany);
    expect(result.success).toBe(false);
    if (!result.success) {
      expect(result.error.issues.some((iss) => /최대 6/.test(iss.message))).toBe(true);
    }
  });

  it('프리셋_라벨_항목_6종_정확히_허용', () => {
    const exactly6 = {
      name: '6종 프리셋',
      eventTypeCd: 'FALL' as const,
      items: Array.from({ length: 6 }, (_, i) => ({ ...baseItem, name: `항목${i + 1}` })),
    };
    expect(presetSchema.safeParse(exactly6).success).toBe(true);
  });

  it('프리셋_이름_특수문자_거부', () => {
    const bad = {
      name: '<script>alert(1)</script>',
      eventTypeCd: 'FALL' as const,
      items: [baseItem],
    };
    expect(presetSchema.safeParse(bad).success).toBe(false);
  });

  it('프리셋_이름_정상_허용', () => {
    const ok = {
      name: '화재-표준_v1',
      eventTypeCd: 'FALL' as const,
      items: [baseItem],
    };
    expect(presetSchema.safeParse(ok).success).toBe(true);
  });

  it('색상_RGB_정규식_검증', () => {
    const bad = {
      name: '테스트',
      eventTypeCd: 'FALL' as const,
      items: [{ ...baseItem, color: 'red' }],
    };
    expect(presetSchema.safeParse(bad).success).toBe(false);
  });
});
