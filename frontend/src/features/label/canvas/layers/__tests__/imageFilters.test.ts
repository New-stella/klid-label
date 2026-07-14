import { describe, expect, it } from 'vitest';

import { DEFAULT_IMAGE_ADJUST } from '@/stores/useLabelStore';

import {
  BRIGHTEN_FILTER,
  CONTRAST_FILTER,
  buildImageFilters,
} from '../imageFilters';

describe('buildImageFilters — konva 밝기/대비 필터 구성 (2c)', () => {
  it('default_원본값이면_필터없음', () => {
    const res = buildImageFilters(DEFAULT_IMAGE_ADJUST);
    expect(res.hasFilters).toBe(false);
    expect(res.filters).toHaveLength(0);
  });

  it('밝기_설정시_Brighten_필터_포함', () => {
    const res = buildImageFilters({ ...DEFAULT_IMAGE_ADJUST, brightness: 0.5 });
    expect(res.hasFilters).toBe(true);
    expect(res.filters).toContain(BRIGHTEN_FILTER);
    expect(res.brightness).toBe(0.5);
  });

  it('대비_설정시_Contrast_필터_포함', () => {
    const res = buildImageFilters({ ...DEFAULT_IMAGE_ADJUST, contrast: 30 });
    expect(res.filters).toContain(CONTRAST_FILTER);
    expect(res.contrast).toBe(30);
  });

  it('밝기_대비_동시설정시_두_필터_모두', () => {
    const res = buildImageFilters({ ...DEFAULT_IMAGE_ADJUST, brightness: -0.3, contrast: 20 });
    expect(res.filters).toContain(BRIGHTEN_FILTER);
    expect(res.filters).toContain(CONTRAST_FILTER);
    expect(res.filters).toHaveLength(2);
  });
});
