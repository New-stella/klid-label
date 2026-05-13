import { describe, expect, it } from 'vitest';

import { labelCodeOptionSchema, presetSchema } from '@/features/preset/schemas';

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

  it('eventTypeCd_빈_문자열_허용_미매핑', () => {
    const ok = {
      name: '프리셋',
      description: '',
      labelCodes: ['PERSON'],
      eventTypeCd: '',
    };
    expect(presetSchema.safeParse(ok).success).toBe(true);
  });

  it('eventTypeCd_올바른_패턴_허용', () => {
    const ok = {
      name: '프리셋',
      description: '',
      labelCodes: ['PERSON'],
      eventTypeCd: 'EVT_FALL',
    };
    expect(presetSchema.safeParse(ok).success).toBe(true);
  });

  it('eventTypeCd_잘못된_형식_거부', () => {
    const bad = {
      name: '프리셋',
      description: '',
      labelCodes: ['PERSON'],
      eventTypeCd: 'invalid-event',
    };
    expect(presetSchema.safeParse(bad).success).toBe(false);
  });

  // ── Phase 3: labelCodeOptions ──
  describe('labelCodeOptionSchema', () => {
    it('BBOX_와_POLYGON_모두_true_허용', () => {
      const ok = { code: 'PERSON', bboxEnabled: true, polygonEnabled: true };
      expect(labelCodeOptionSchema.safeParse(ok).success).toBe(true);
    });

    it('BBOX_만_true_허용', () => {
      const ok = { code: 'PERSON', bboxEnabled: true, polygonEnabled: false };
      expect(labelCodeOptionSchema.safeParse(ok).success).toBe(true);
    });

    it('POLYGON_만_true_허용', () => {
      const ok = { code: 'PERSON', bboxEnabled: false, polygonEnabled: true };
      expect(labelCodeOptionSchema.safeParse(ok).success).toBe(true);
    });

    it('presetSchema_둘_다_false_인_옵션_거부', () => {
      const bad = {
        name: '프리셋',
        description: '',
        labelCodeOptions: [
          { code: 'PERSON', bboxEnabled: false, polygonEnabled: false },
        ],
      };
      const r = presetSchema.safeParse(bad);
      expect(r.success).toBe(false);
      if (!r.success) {
        expect(
          r.error.issues.some((iss) =>
            /BBOX|POLYGON|최소 하나/.test(iss.message),
          ),
        ).toBe(true);
      }
    });

    it('빈_code_거부', () => {
      const bad = { code: '', bboxEnabled: true, polygonEnabled: true };
      expect(labelCodeOptionSchema.safeParse(bad).success).toBe(false);
    });

    it('code_32자_초과_거부', () => {
      const bad = {
        code: 'A'.repeat(33),
        bboxEnabled: true,
        polygonEnabled: true,
      };
      expect(labelCodeOptionSchema.safeParse(bad).success).toBe(false);
    });
  });

  describe('presetSchema labelCodeOptions', () => {
    it('labelCodeOptions_정상_프리셋_허용', () => {
      const ok = {
        name: '프리셋',
        description: '',
        labelCodeOptions: [
          { code: 'PERSON', bboxEnabled: true, polygonEnabled: false },
          { code: 'VEHICLE', bboxEnabled: true, polygonEnabled: true },
        ],
      };
      expect(presetSchema.safeParse(ok).success).toBe(true);
    });

    it('labelCodeOptions_없고_labelCodes_만_있어도_허용_레거시_호환', () => {
      const ok = {
        name: '프리셋',
        description: '',
        labelCodes: ['PERSON', 'VEHICLE'],
      };
      const r = presetSchema.safeParse(ok);
      expect(r.success).toBe(true);
      if (r.success) {
        expect(r.data.labelCodeOptions).toHaveLength(2);
        expect(r.data.labelCodeOptions[0]).toMatchObject({
          code: 'PERSON',
          bboxEnabled: true,
          polygonEnabled: true,
        });
      }
    });

    it('labelCodeOptions_빈_배열_거부', () => {
      const bad = {
        name: '프리셋',
        description: '',
        labelCodeOptions: [],
      };
      expect(presetSchema.safeParse(bad).success).toBe(false);
    });

    it('labelCodeOptions_21개_거부', () => {
      const bad = {
        name: '프리셋',
        description: '',
        labelCodeOptions: Array.from({ length: 21 }, (_, i) => ({
          code: `LABEL_${i}`,
          bboxEnabled: true,
          polygonEnabled: true,
        })),
      };
      expect(presetSchema.safeParse(bad).success).toBe(false);
    });
  });
});
