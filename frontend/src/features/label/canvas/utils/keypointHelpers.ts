// Phase 3: 키포인트(COCO-17 휴먼 포즈) 렌더 헬퍼.
// - 스켈레톤 엣지(COCO_SKELETON, 1-indexed) → konva Line 좌표쌍 변환
// - 가시성(v)별 시각 스타일
// 이미지↔캔버스 변환은 coordinateTransformer 재사용.

import type { KeypointShape } from '../../types';

import { translateToCanvas, type Geometry } from './coordinateTransformer';

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
