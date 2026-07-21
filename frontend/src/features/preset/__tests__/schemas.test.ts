import { describe, expect, it } from 'vitest';

import { presetSchema } from '@/features/preset/schemas';

describe('preset schemas (labelIds)', () => {
  it('labelId_1개_이상_요구', () => {
    const empty = { name: '테스트 프리셋', description: '', labelIds: [] };
    const result = presetSchema.safeParse(empty);
    expect(result.success).toBe(false);
    if (!result.success) {
      expect(result.error.issues.some((iss) => /1개 이상/.test(iss.message))).toBe(true);
    }
  });

  it('labelId_최대_20개_제한', () => {
    const tooMany = {
      name: '테스트',
      description: '',
      labelIds: Array.from({ length: 21 }, (_, i) => i + 1),
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
      labelIds: [1, 2, 3],
    };
    expect(presetSchema.safeParse(ok).success).toBe(true);
  });

  it('labelId_음수_거부', () => {
    const bad = { name: '프리셋', description: '', labelIds: [-1] };
    expect(presetSchema.safeParse(bad).success).toBe(false);
  });

  it('labelId_0_거부', () => {
    const bad = { name: '프리셋', description: '', labelIds: [0] };
    expect(presetSchema.safeParse(bad).success).toBe(false);
  });

  it('labelId_소수_거부', () => {
    const bad = { name: '프리셋', description: '', labelIds: [1.5] };
    expect(presetSchema.safeParse(bad).success).toBe(false);
  });

  it('이름_빈_문자열_거부', () => {
    const bad = { name: '', description: '', labelIds: [1] };
    expect(presetSchema.safeParse(bad).success).toBe(false);
  });

  it('이름_64자_초과_거부', () => {
    const bad = { name: 'a'.repeat(65), description: '', labelIds: [1] };
    expect(presetSchema.safeParse(bad).success).toBe(false);
  });

  it('설명_optional_허용', () => {
    const ok = { name: '프리셋', labelIds: [1] };
    expect(presetSchema.safeParse(ok).success).toBe(true);
  });

  it('eventTypeCd_빈_문자열_허용_미매핑', () => {
    const ok = { name: '프리셋', description: '', labelIds: [1], eventTypeCd: '' };
    expect(presetSchema.safeParse(ok).success).toBe(true);
  });

  it('eventTypeCd_올바른_패턴_허용', () => {
    const ok = { name: '프리셋', description: '', labelIds: [1], eventTypeCd: 'EVT_FALL' };
    expect(presetSchema.safeParse(ok).success).toBe(true);
  });

  it('eventTypeCd_잘못된_형식_거부', () => {
    const bad = { name: '프리셋', description: '', labelIds: [1], eventTypeCd: 'invalid-event' };
    expect(presetSchema.safeParse(bad).success).toBe(false);
  });
});
