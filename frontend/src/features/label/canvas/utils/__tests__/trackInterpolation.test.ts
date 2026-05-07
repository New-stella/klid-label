// 트랙 보간 클라이언트 보조 테스트.
// BE TrackInterpolator가 정답이며, FE는 시각 미리보기용 보조.

import { describe, expect, it } from 'vitest';

import { interpolateBBox, interpolateTrack, type Keyframe } from '../trackInterpolation';

describe('trackInterpolation', () => {
  describe('interpolateBBox_단일_키프레임_사이_선형_보간', () => {
    it('중간_프레임_절반_지점', () => {
      const a = { left: 0, top: 0, right: 100, bottom: 100 };
      const b = { left: 100, top: 100, right: 200, bottom: 200 };
      const mid = interpolateBBox(a, b, 0.5);
      expect(mid.left).toBeCloseTo(50, 5);
      expect(mid.top).toBeCloseTo(50, 5);
      expect(mid.right).toBeCloseTo(150, 5);
      expect(mid.bottom).toBeCloseTo(150, 5);
    });

    it('t_0이면_a_t_1이면_b', () => {
      const a = { left: 10, top: 20, right: 50, bottom: 60 };
      const b = { left: 30, top: 40, right: 90, bottom: 120 };
      expect(interpolateBBox(a, b, 0)).toEqual(a);
      expect(interpolateBBox(a, b, 1)).toEqual(b);
    });
  });

  describe('interpolateTrack_프레임_시퀀스_보간', () => {
    it('두_키프레임_사이_프레임_보간', () => {
      const keyframes: Keyframe[] = [
        { frameNo: 0, bbox: { left: 0, top: 0, right: 10, bottom: 10 } },
        { frameNo: 10, bbox: { left: 100, top: 100, right: 110, bottom: 110 } },
      ];
      const result = interpolateTrack(keyframes, 5);
      expect(result.left).toBeCloseTo(50, 5);
      expect(result.top).toBeCloseTo(50, 5);
    });

    it('키프레임이_하나일때는_그대로_반환', () => {
      const keyframes: Keyframe[] = [
        { frameNo: 3, bbox: { left: 1, top: 2, right: 3, bottom: 4 } },
      ];
      const result = interpolateTrack(keyframes, 7);
      expect(result).toEqual(keyframes[0].bbox);
    });

    it('frame이_첫_키프레임_이전이면_첫_키프레임', () => {
      const keyframes: Keyframe[] = [
        { frameNo: 5, bbox: { left: 0, top: 0, right: 10, bottom: 10 } },
        { frameNo: 10, bbox: { left: 100, top: 100, right: 110, bottom: 110 } },
      ];
      const result = interpolateTrack(keyframes, 0);
      expect(result).toEqual(keyframes[0].bbox);
    });

    it('frame이_마지막_키프레임_이후면_마지막', () => {
      const keyframes: Keyframe[] = [
        { frameNo: 0, bbox: { left: 0, top: 0, right: 10, bottom: 10 } },
        { frameNo: 5, bbox: { left: 50, top: 50, right: 60, bottom: 60 } },
      ];
      const result = interpolateTrack(keyframes, 100);
      expect(result).toEqual(keyframes[1].bbox);
    });
  });

  describe('빈_키프레임', () => {
    it('빈_배열은_에러', () => {
      expect(() => interpolateTrack([], 5)).toThrow(/keyframes/i);
    });
  });
});
