import { describe, expect, it } from 'vitest';

import {
  flattenPoints,
  getFitScale,
  pointsToBBox,
} from '../utils/coordinates';

describe('getFitScale', () => {
  it('getFitScale_1920x1080_컨테이너_960x540_scale_0_5_offset_0_0', () => {
    const r = getFitScale(1920, 1080, 960, 540);
    expect(r.scale).toBeCloseTo(0.5);
    expect(r.offsetX).toBeCloseTo(0);
    expect(r.offsetY).toBeCloseTo(0);
  });

  it('getFitScale_세로형_이미지_가로_컨테이너_offset_여백_적절', () => {
    // 1080x1920 (세로형) → 960x540 컨테이너에 fit
    // scale = min(960/1080, 540/1920) = min(0.888, 0.281) = 0.281
    // scaled = 1080*0.281, 1920*0.281 = 303.75, 540
    // offsetX = (960 - 303.75)/2 = 328.125, offsetY = 0
    const r = getFitScale(1080, 1920, 960, 540);
    expect(r.scale).toBeCloseTo(540 / 1920);
    expect(r.offsetX).toBeGreaterThan(0);
    expect(r.offsetY).toBeCloseTo(0);
  });

  it('getFitScale_가로형_이미지_세로_컨테이너_offsetY_생성', () => {
    // 1920x1080 → 540x960 컨테이너
    // scale = min(540/1920, 960/1080) = min(0.281, 0.888) = 0.281
    // scaledH = 1080 * 0.281 = 303.75
    // offsetY = (960 - 303.75)/2
    const r = getFitScale(1920, 1080, 540, 960);
    expect(r.scale).toBeCloseTo(540 / 1920);
    expect(r.offsetX).toBeCloseTo(0);
    expect(r.offsetY).toBeGreaterThan(0);
  });

  it('getFitScale_이미지_크기_0_시_안전_기본값', () => {
    expect(getFitScale(0, 1080, 960, 540)).toEqual({ scale: 1, offsetX: 0, offsetY: 0 });
    expect(getFitScale(1920, 0, 960, 540)).toEqual({ scale: 1, offsetX: 0, offsetY: 0 });
  });

  it('getFitScale_컨테이너_크기_0_시_안전_기본값', () => {
    expect(getFitScale(1920, 1080, 0, 540)).toEqual({ scale: 1, offsetX: 0, offsetY: 0 });
    expect(getFitScale(1920, 1080, 960, 0)).toEqual({ scale: 1, offsetX: 0, offsetY: 0 });
  });
});

describe('pointsToBBox', () => {
  it('2점_대각선_사각형_변환', () => {
    const box = pointsToBBox([
      [10, 20],
      [110, 220],
    ]);
    expect(box).toEqual({ x: 10, y: 20, width: 100, height: 200 });
  });

  it('4점_사각형_변환', () => {
    const box = pointsToBBox([
      [10, 20],
      [110, 20],
      [110, 220],
      [10, 220],
    ]);
    expect(box).toEqual({ x: 10, y: 20, width: 100, height: 200 });
  });

  it('폴리곤_N점_min_max_사각형', () => {
    const box = pointsToBBox([
      [50, 50],
      [100, 30],
      [150, 80],
      [80, 100],
    ]);
    expect(box).toEqual({ x: 50, y: 30, width: 100, height: 70 });
  });

  it('빈_배열_0_사각형', () => {
    expect(pointsToBBox([])).toEqual({ x: 0, y: 0, width: 0, height: 0 });
  });

  it('잘못된_점_무시', () => {
    const box = pointsToBBox([
      [10, 20],
      [] as unknown as number[],
      [110, 220],
    ]);
    expect(box).toEqual({ x: 10, y: 20, width: 100, height: 200 });
  });
});

describe('flattenPoints', () => {
  it('2D_배열_평탄화', () => {
    expect(
      flattenPoints([
        [10, 20],
        [30, 40],
      ]),
    ).toEqual([10, 20, 30, 40]);
  });

  it('빈_배열_빈_결과', () => {
    expect(flattenPoints([])).toEqual([]);
  });
});
