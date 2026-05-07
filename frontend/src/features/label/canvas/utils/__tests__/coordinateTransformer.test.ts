import { describe, expect, it } from 'vitest';

import {
  clamp,
  computeWrappingBox,
  rotate2DPoints,
  translateFromCanvas,
  translateToCanvas,
  type Geometry,
} from '../coordinateTransformer';

function makeGeometry(overrides: Partial<Geometry> = {}): Geometry {
  return {
    image: { width: 1920, height: 1080 },
    canvas: { width: 960, height: 540 },
    scale: 0.5,
    top: 0,
    left: 0,
    angle: 0,
    ...overrides,
  };
}

describe('coordinateTransformer', () => {
  describe('translateToCanvas / translateFromCanvas (라운드트립)', () => {
    it('좌표_변환_translateFromCanvas_↔_translateToCanvas_라운드트립', () => {
      const geom = makeGeometry({ scale: 0.5, top: 20, left: 40 });
      const original = { x: 800, y: 600 };

      const canvas = translateToCanvas(geom, original.x, original.y);
      const back = translateFromCanvas(geom, canvas.x, canvas.y);

      expect(back.x).toBeCloseTo(original.x, 5);
      expect(back.y).toBeCloseTo(original.y, 5);
    });

    it('translateToCanvas_scale_offset_적용', () => {
      const geom = makeGeometry({ scale: 0.5, top: 10, left: 20 });
      const point = translateToCanvas(geom, 100, 200);
      // imageX * scale + left, imageY * scale + top
      expect(point.x).toBeCloseTo(100 * 0.5 + 20, 5);
      expect(point.y).toBeCloseTo(200 * 0.5 + 10, 5);
    });

    it('회전_각도_있는_상태에서도_라운드트립_유지', () => {
      const geom = makeGeometry({ scale: 0.8, top: 5, left: 15, angle: 30 });
      const original = { x: 400, y: 300 };

      const canvas = translateToCanvas(geom, original.x, original.y);
      const back = translateFromCanvas(geom, canvas.x, canvas.y);

      expect(back.x).toBeCloseTo(original.x, 4);
      expect(back.y).toBeCloseTo(original.y, 4);
    });
  });

  describe('rotate2DPoints', () => {
    it('회전_90도_적용시_rotate2DPoints_결과_검증', () => {
      const result = rotate2DPoints(0, 0, 90, [10, 0]);
      expect(result[0]).toBeCloseTo(0, 5);
      expect(result[1]).toBeCloseTo(10, 5);
    });

    it('회전_360도는_원위치로_복귀', () => {
      const result = rotate2DPoints(50, 50, 360, [10, 20]);
      expect(result[0]).toBeCloseTo(10, 5);
      expect(result[1]).toBeCloseTo(20, 5);
    });

    it('여러_점_배열_회전', () => {
      const result = rotate2DPoints(0, 0, 180, [10, 0, 0, 10]);
      expect(result[0]).toBeCloseTo(-10, 5);
      expect(result[1]).toBeCloseTo(0, 5);
      expect(result[2]).toBeCloseTo(0, 5);
      expect(result[3]).toBeCloseTo(-10, 5);
    });
  });

  describe('computeWrappingBox', () => {
    it('점_배열의_외접_사각형_계산', () => {
      const box = computeWrappingBox([10, 20, 100, 5, 50, 80]);
      expect(box.left).toBe(10);
      expect(box.top).toBe(5);
      expect(box.right).toBe(100);
      expect(box.bottom).toBe(80);
    });

    it('단일_점도_정상_처리', () => {
      const box = computeWrappingBox([42, 7]);
      expect(box.left).toBe(42);
      expect(box.right).toBe(42);
      expect(box.top).toBe(7);
      expect(box.bottom).toBe(7);
    });
  });

  describe('clamp', () => {
    it('범위_내는_그대로', () => {
      expect(clamp(5, 0, 10)).toBe(5);
    });
    it('하한_이하는_min', () => {
      expect(clamp(-1, 0, 10)).toBe(0);
    });
    it('상한_이상은_max', () => {
      expect(clamp(99, 0, 10)).toBe(10);
    });
  });
});
