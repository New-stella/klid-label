// Phase 3: 키포인트(COCO-17 휴먼 포즈) 렌더 헬퍼.
// - 스켈레톤 엣지(COCO_SKELETON, 1-indexed) → konva Line 좌표쌍 변환
// - 가시성(v)별 시각 스타일
// 이미지↔캔버스 변환은 coordinateTransformer 재사용.

import type { KeypointShape } from '../../types';

import { translateToCanvas, type Geometry } from './coordinateTransformer';

/**
 * COCO-17 관절명 한글 병렬 맵 — **표시 전용**. 인덱스 순서는 {@link import('../../types').KEYPOINT_NAMES}
 * (영문·BE 대조 정본)와 1:1 대응하며, 저장·export·BE 대조에는 영문 상수만 사용한다.
 */
export const KEYPOINT_NAMES_KO = [
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
] as const;

/**
 * 인체 다이어그램 가이드 하단 캡션 — **표시 전용**. 다이어그램은 COCO 표준 미러(인물 좌측=뷰어 우측)로
 * 그려지므로, 작업자가 좌·우를 뷰어 기준으로 오해하지 않도록 "인물(피사체) 기준"임을 명시한다.
 */
export const KEYPOINT_SUBJECT_ORIENTATION_CAPTION = '※ 좌·우는 인물 기준';

/**
 * 인체 다이어그램 가이드용 정규화 좌표(viewBox 0~100 기준, y는 아래 방향) — **표시 전용**.
 * 표준 정면 포즈로 17관절을 배치. COCO left_*(인물 좌측)은 정면 뷰에서 뷰어 우측(x 큼),
 * right_*(인물 우측)은 뷰어 좌측(x 작음)으로 미러 반영한다. 인덱스는 KEYPOINT_NAMES 순서.
 */
export const KEYPOINT_GUIDE_LAYOUT: readonly { x: number; y: number }[] = [
  { x: 50, y: 12 }, // 0 nose
  { x: 54, y: 9 }, // 1 left_eye  (뷰어 우측)
  { x: 46, y: 9 }, // 2 right_eye (뷰어 좌측)
  { x: 58, y: 11 }, // 3 left_ear
  { x: 42, y: 11 }, // 4 right_ear
  { x: 62, y: 26 }, // 5 left_shoulder
  { x: 38, y: 26 }, // 6 right_shoulder
  { x: 69, y: 41 }, // 7 left_elbow
  { x: 31, y: 41 }, // 8 right_elbow
  { x: 73, y: 55 }, // 9 left_wrist
  { x: 27, y: 55 }, // 10 right_wrist
  { x: 58, y: 56 }, // 11 left_hip
  { x: 42, y: 56 }, // 12 right_hip
  { x: 60, y: 75 }, // 13 left_knee
  { x: 40, y: 75 }, // 14 right_knee
  { x: 61, y: 93 }, // 15 left_ankle
  { x: 39, y: 93 }, // 16 right_ankle
] as const;

export interface KeypointVisibilityStyle {
  /** 렌더 불투명도 (v=2 불투명 / v=1 반투명 / v=0 흐림). */
  opacity: number;
  /** 점선 패턴 (v=1 비가시 관절 구분용). undefined 면 실선. */
  dash?: number[];
  /** 스켈레톤 연결선·앵커를 표시할지 (v=0 은 숨김). */
  visible: boolean;
}

// v=1(비가시) 관절 반투명 표시 불투명도.
const OCCLUDED_OPACITY = 0.4;
// v=0(미표기) 관절 흐림 표시 불투명도.
const UNLABELED_OPACITY = 0.15;

/**
 * 가시성 코드(v)별 스타일.
 * - v=2 가시: 실선·불투명
 * - v=1 비가시(occluded): 반투명·점선
 * - v=0 미표기: 숨김(흐림)
 */
export function visibilityStyle(v: number): KeypointVisibilityStyle {
  if (v === 2) return { opacity: 1, visible: true };
  if (v === 1) return { opacity: OCCLUDED_OPACITY, dash: [4, 4], visible: true };
  return { opacity: UNLABELED_OPACITY, visible: false };
}

/**
 * 가시성(v) 순환 — Alt+클릭 시 2(가시) → 1(비가시) → 0(미표기) → 2 로 회전.
 * 항상 {0,1,2} 안에서만 돌아 범위를 벗어나지 않는다(입력 검증 가드).
 */
export function cycleVisibility(v: number): number {
  if (v === 2) return 1;
  if (v === 1) return 0;
  return 2;
}

/**
 * 스켈레톤 엣지(1-indexed 관절 번호쌍)를 canvas Line 좌표 [x1,y1,x2,y2] 로 변환.
 * - 두 끝점 중 하나라도 미배치(인덱스 밖) 또는 v=0(미표기) 이면 null(선 숨김).
 * 부분 배치(draft) 중에도 안전하게 호출 가능.
 */
export function skeletonEdgeToCanvasLine(
  geom: Geometry,
  keypoints: KeypointShape['keypoints'],
  edge: readonly [number, number] | readonly number[],
): number[] | null {
  const a = edge[0];
  const b = edge[1];
  const ka = keypoints[a - 1];
  const kb = keypoints[b - 1];
  if (!ka || !kb) return null;
  if (ka.v === 0 || kb.v === 0) return null;
  const pa = translateToCanvas(geom, ka.x, ka.y);
  const pb = translateToCanvas(geom, kb.x, kb.y);
  return [pa.x, pa.y, pb.x, pb.y];
}
