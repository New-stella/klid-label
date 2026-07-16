// Phase 3: 키포인트 상수 — BE common/util/KeypointSkeleton.java 와 값·순서 정확히 일치해야 렌더가 깨지지 않음.
// (BE KEYPOINT_NAMES 17 / SKELETON_EDGES 19쌍 1-indexed 를 하드코딩 대조)

import { describe, expect, it } from 'vitest';

import { COCO_SKELETON, KEYPOINT_NAMES, ShapeType, ToolType } from '../types';

// BE KeypointSkeleton.java 의 값을 그대로 옮긴 정본 (대조 기준).
const BE_KEYPOINT_NAMES = [
  'nose',
  'left_eye',
  'right_eye',
  'left_ear',
  'right_ear',
  'left_shoulder',
  'right_shoulder',
  'left_elbow',
  'right_elbow',
  'left_wrist',
  'right_wrist',
  'left_hip',
  'right_hip',
  'left_knee',
  'right_knee',
  'left_ankle',
  'right_ankle',
];

const BE_SKELETON_EDGES = [
  [16, 14],
  [14, 12],
  [17, 15],
  [15, 13],
  [12, 13],
  [6, 12],
  [7, 13],
  [6, 7],
  [6, 8],
  [7, 9],
  [8, 10],
  [9, 11],
  [2, 3],
  [1, 2],
  [1, 3],
  [2, 4],
  [3, 5],
  [4, 6],
  [5, 7],
];

describe('키포인트 상수 (types.ts) — BE KeypointSkeleton 대조', () => {
  it('types_KEYPOINT_NAMES_17개_BE와_일치', () => {
    expect(KEYPOINT_NAMES).toHaveLength(17);
    expect([...KEYPOINT_NAMES]).toEqual(BE_KEYPOINT_NAMES);
  });

  it('types_COCO_SKELETON_19쌍_BE와_일치', () => {
    expect(COCO_SKELETON).toHaveLength(19);
    expect(COCO_SKELETON.map((e) => [...e])).toEqual(BE_SKELETON_EDGES);
  });

  it('ShapeType_ToolType_KEYPOINT_추가_기존값_회귀없음', () => {
    expect(ShapeType.KEYPOINT).toBe('KEYPOINT');
    expect(ToolType.KEYPOINT).toBe('KEYPOINT');
    // 기존 값 회귀 없음
    expect(ShapeType.BBOX).toBe('BBOX');
    expect(ShapeType.POLYGON).toBe('POLYGON');
    expect(ToolType.BBOX).toBe('BBOX');
    expect(ToolType.POLYGON).toBe('POLYGON');
    expect(ToolType.SELECT).toBe('SELECT');
  });
});
