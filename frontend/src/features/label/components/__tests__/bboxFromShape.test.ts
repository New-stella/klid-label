// event_annotation evidence 자동연결 — shape → obj_bbox 도출 순수 함수 단위 테스트.

import { describe, expect, it } from 'vitest';

import type { Shape } from '@/features/label/types';
import { bboxFromShape } from '../eventAnnotationShared';

describe('bboxFromShape', () => {
  it('BBOX_좌표_그대로_반환', () => {
    const shape: Shape = { type: 'BBOX', left: 10, top: 20, right: 30, bottom: 40 };
    expect(bboxFromShape(shape)).toEqual([10, 20, 30, 40]);
  });

  it('POLYGON_points_minmax_bbox', () => {
    // points = [x1,y1,x2,y2,...] → min/max 로 외접 bbox
    const shape: Shape = { type: 'POLYGON', points: [15, 60, 40, 20, 25, 55] };
    expect(bboxFromShape(shape)).toEqual([15, 20, 40, 60]);
  });

  it('KEYPOINT_유효점_minmax_bbox(v0_제외)', () => {
    // v==0 인 점은 미표기 → 제외. 유효점(v>0)만 min/max.
    const shape: Shape = {
      type: 'KEYPOINT',
      keypoints: [
        { x: 5, y: 5, v: 0 }, // 제외
        { x: 100, y: 200, v: 2 },
        { x: 50, y: 80, v: 1 },
      ],
    };
    expect(bboxFromShape(shape)).toEqual([50, 80, 100, 200]);
  });

  it('MASK_null_반환', () => {
    const shape: Shape = { type: 'MASK', rle: 'x' };
    expect(bboxFromShape(shape)).toBeNull();
  });

  it('KEYPOINT_유효점없으면_null', () => {
    const shape: Shape = {
      type: 'KEYPOINT',
      keypoints: [
        { x: 5, y: 5, v: 0 },
        { x: 9, y: 9, v: 0 },
      ],
    };
    expect(bboxFromShape(shape)).toBeNull();
  });
});
