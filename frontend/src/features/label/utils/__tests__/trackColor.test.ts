import { describe, expect, it } from 'vitest';

import { trackIdToColor } from '../trackColor';

describe('trackIdToColor', () => {
  it('같은_trackId_는_같은_HSL_색상_반환_(결정성)', () => {
    expect(trackIdToColor('42')).toBe(trackIdToColor('42'));
    expect(trackIdToColor('abc123')).toBe(trackIdToColor('abc123'));
  });

  it('null_은_회색_fallback', () => {
    expect(trackIdToColor(null)).toBe('hsl(0, 0%, 60%)');
  });

  it('undefined_는_회색_fallback', () => {
    expect(trackIdToColor(undefined)).toBe('hsl(0, 0%, 60%)');
  });

  it('빈_문자열_은_회색_fallback', () => {
    expect(trackIdToColor('')).toBe('hsl(0, 0%, 60%)');
  });

  it('다른_trackId_는_다른_HSL_색상_가능성_(해시_분포)', () => {
    // 다양한 trackId 가 모두 동일 색으로 떨어지진 않음을 확인
    const colors = new Set([
      trackIdToColor('1'),
      trackIdToColor('2'),
      trackIdToColor('3'),
      trackIdToColor('100'),
      trackIdToColor('hello'),
    ]);
    expect(colors.size).toBeGreaterThan(1);
  });

  it('HSL_포맷_검증_hue_0_360_미만', () => {
    const out = trackIdToColor('xyz');
    const match = out.match(/^hsl\((\d+), 70%, 50%\)$/);
    expect(match).not.toBeNull();
    const hue = Number(match![1]);
    expect(hue).toBeGreaterThanOrEqual(0);
    expect(hue).toBeLessThan(360);
  });
});
