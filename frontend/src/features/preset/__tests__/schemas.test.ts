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

  it('eventTypeCd_20자는_허용_21자는_거부_컬럼폭_정합', () => {
    // 진실원: 코드값 표준도메인 VARCHAR(20) = 컬럼 LS_LABEL_PRESET.EVNT_TYPE_CD.
    // FE 상한이 32 였던 동안 21~32자는 FE 를 통과해 전송된 뒤 BE 400 으로 되돌아왔다.
    const ok = { name: '프리셋', description: '', labelIds: [1], eventTypeCd: 'A'.repeat(20) };
    expect(presetSchema.safeParse(ok).success).toBe(true);

    const bad = { name: '프리셋', description: '', labelIds: [1], eventTypeCd: 'A'.repeat(21) };
    expect(presetSchema.safeParse(bad).success).toBe(false);
  });
});
