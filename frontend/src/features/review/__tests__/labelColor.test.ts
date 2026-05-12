import { describe, expect, it } from 'vitest';

import { colorForLabel, fillForLabel } from '../utils/labelColor';

describe('colorForLabel', () => {
  it('colorForLabel_사람_차량_트럭_고정색_반환', () => {
    expect(colorForLabel('사람')).toBe('#ef4444');
    expect(colorForLabel('차량')).toBe('#eab308');
    expect(colorForLabel('트럭')).toBe('#22c55e');
    expect(colorForLabel('오토바이')).toBe('#22d3ee');
    expect(colorForLabel('자전거')).toBe('#06b6d4');
    expect(colorForLabel('화재')).toBe('#f97316');
  });

  it('colorForLabel_미정의_라벨_hsl_생성_동일_입력_동일_출력', () => {
    const c1 = colorForLabel('우주선');
    const c2 = colorForLabel('우주선');
    expect(c1).toBe(c2);
    expect(c1).toMatch(/^hsl\(\d+, 65%, 55%\)$/);
  });

  it('colorForLabel_다른_라벨_다른_색상', () => {
    // 라벨명에 따라 hue 분포 — 동일하지 않아야 한다.
    const a = colorForLabel('새');
    const b = colorForLabel('비행기');
    expect(a).not.toBe(b);
  });

  it('colorForLabel_빈_라벨_기본_slate', () => {
    expect(colorForLabel('')).toBe('#94a3b8');
  });
});

describe('fillForLabel', () => {
  it('hex_stroke_시_8자리_hex_변환', () => {
    const fill = fillForLabel('사람', 0.2);
    // 0.2 * 255 = 51 = 0x33
    expect(fill).toBe('#ef444433');
  });

  it('hsl_stroke_시_hsla_변환', () => {
    const fill = fillForLabel('우주선', 0.2);
    expect(fill).toMatch(/^hsla\(\d+, 65%, 55%, 0\.2\)$/);
  });

  it('alpha_범위_보정', () => {
    // 1 초과 → 1 로 clamp
    const fill = fillForLabel('사람', 2);
    expect(fill).toBe('#ef4444ff');
    // 0 미만 → 0 로 clamp
    expect(fillForLabel('사람', -1)).toBe('#ef444400');
  });
});
