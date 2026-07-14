// 키포인트 배치 가이드 상수 — 한글 관절명(KEYPOINT_NAMES_KO) + 다이어그램 정규화 좌표
// (KEYPOINT_GUIDE_LAYOUT). 표시 전용이며 BE 대조 상수(KEYPOINT_NAMES)는 불변이어야 한다.

import { describe, expect, it } from 'vitest';

import { KEYPOINT_NAMES } from '../types';
import { KEYPOINT_GUIDE_LAYOUT, KEYPOINT_NAMES_KO } from '../canvas/utils/keypointHelpers';

// COCO 순서(0-based) 한글 정본 — 화면 표시 대조 기준.
const EXPECTED_KO = [
  '코',
  '왼쪽 눈',
  '오른쪽 눈',
  '왼쪽 귀',
  '오른쪽 귀',
  '왼쪽 어깨',
  '오른쪽 어깨',
  '왼쪽 팔꿈치',
  '오른쪽 팔꿈치',
  '왼쪽 손목',
  '오른쪽 손목',
  '왼쪽 엉덩이',
  '오른쪽 엉덩이',
  '왼쪽 무릎',
  '오른쪽 무릎',
  '왼쪽 발목',
  '오른쪽 발목',
];

// BE KeypointSkeleton.java 정본 — 영문 상수 불변 회귀 대조.
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

describe('키포인트 가이드 상수', () => {
  it('KEYPOINT_NAMES_KO_17개_COCO순서_정합', () => {
    // 길이·순서가 영문 상수와 1:1 대응해야 함
    expect(KEYPOINT_NAMES_KO).toHaveLength(17);
    expect(KEYPOINT_NAMES_KO).toHaveLength(KEYPOINT_NAMES.length);
    expect([...KEYPOINT_NAMES_KO]).toEqual(EXPECTED_KO);
  });

  it('KEYPOINT_NAMES_영문_불변_회귀', () => {
    // 저장·export·BE 대조용 영문 상수는 그대로 유지되어야 함(한글 추가로 회귀 없음)
    expect(KEYPOINT_NAMES).toHaveLength(17);
    expect([...KEYPOINT_NAMES]).toEqual(BE_KEYPOINT_NAMES);
  });

  it('KEYPOINT_GUIDE_LAYOUT_17좌표_뷰박스_범위내', () => {
    // 17개 정규화 좌표(0~100 viewBox) — 표준 정면 포즈
    expect(KEYPOINT_GUIDE_LAYOUT).toHaveLength(17);
    for (const p of KEYPOINT_GUIDE_LAYOUT) {
      expect(p.x).toBeGreaterThanOrEqual(0);
      expect(p.x).toBeLessThanOrEqual(100);
      expect(p.y).toBeGreaterThanOrEqual(0);
      expect(p.y).toBeLessThanOrEqual(100);
    }
  });

  it('KEYPOINT_GUIDE_LAYOUT_COCO_left는_뷰어_우측_미러', () => {
    // COCO left_* = 인물 좌측 = 뷰어 우측(x 큼), right_* = 뷰어 좌측(x 작음)
    const leftShoulder = KEYPOINT_GUIDE_LAYOUT[5];
    const rightShoulder = KEYPOINT_GUIDE_LAYOUT[6];
    expect(leftShoulder.x).toBeGreaterThan(rightShoulder.x);
  });
});
