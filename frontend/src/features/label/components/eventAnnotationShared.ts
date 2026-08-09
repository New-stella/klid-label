// Phase 4 — event_annotation 패널 공용 상수·행 타입.
//
// EventAnnotationPanel 과 CaptionCandidateRow/EvidenceCandidateRow 가 공유한다.
// 순환 import 방지를 위해 상수·타입만 이 모듈에 둔다(컴포넌트 import 없음).
//
// 크기 상한은 BE EventAnnotationPayload 의 @Size 계약과 정합(부분 DoS·저장 400 방지):
//   MAX_EVENT_CLASS=100 / MAX_TEXT=4000(question·answer·caption_text·evidence_text)
//   MAX_COT_STEP=2000(cot 각 단계) / MAX_ID=200(obj_id·obj_label 원소).

import type { Shape } from '../types';

/** CoT 는 1·2·3단계 고정 배열. */
export const COT_STEPS = 3;
export const COT_LABELS = ['1단계', '2단계', '3단계'];

/** BE @Size 정합 상한(입력 maxLength 힌트). */
export const MAX_EVENT_CLASS = 100;
export const MAX_TEXT = 4000;
export const MAX_COT_STEP = 2000;
export const MAX_ID = 200;

export const INPUT_CLASS =
  'w-full rounded border border-gray-300 bg-white text-gray-900 text-sm p-1.5 placeholder-gray-400 focus:outline-none focus:ring-1 focus:ring-primary-500';

export interface CaptionRow {
  key: string;
  captionText: string;
  cot: string[];
}

export interface EvidenceRow {
  key: string;
  evidenceText: string;
  frameId: string;
  objId: string;
  objBbox: string;
  objLabel: string;
}

/**
 * 캔버스 선택 라벨의 shape → evidence obj_bbox 용 [x1,y1,x2,y2] 외접 박스 도출(순수 함수).
 * - BBOX: [left, top, right, bottom] 그대로
 * - POLYGON: points([x1,y1,...]) 의 min/max
 * - KEYPOINT: 유효점(v>0)의 x/y min/max — 유효점이 없으면 null
 * - MASK: bbox 도출 불가 → null(호출측이 obj_bbox 를 건너뜀)
 * 좌표는 실수를 그대로 반환한다(정수 반올림·문자열화는 호출측 책임).
 */
export function bboxFromShape(shape: Shape): [number, number, number, number] | null {
  if (shape.type === 'BBOX') {
    return [shape.left, shape.top, shape.right, shape.bottom];
  }
  if (shape.type === 'POLYGON') {
    return minMaxBbox(shape.points);
  }
  if (shape.type === 'KEYPOINT') {
    const flat: number[] = [];
    for (const kp of shape.keypoints) {
      if (kp.v === 0) continue; // 미표기 점 제외
      flat.push(kp.x, kp.y);
    }
    return minMaxBbox(flat);
  }
  return null; // MASK 등 도출 불가
}

/** flat [x1,y1,x2,y2,...] → [minX,minY,maxX,maxY]. 유효 점(2개 이상 좌표)이 없으면 null. */
function minMaxBbox(flat: number[]): [number, number, number, number] | null {
  if (flat.length < 2) return null;
  let minX = Infinity;
  let minY = Infinity;
  let maxX = -Infinity;
  let maxY = -Infinity;
  for (let i = 0; i + 1 < flat.length; i += 2) {
    const x = flat[i];
    const y = flat[i + 1];
    if (x < minX) minX = x;
    if (x > maxX) maxX = x;
    if (y < minY) minY = y;
    if (y > maxY) maxY = y;
  }
  if (!Number.isFinite(minX) || !Number.isFinite(minY)) return null;
  return [minX, minY, maxX, maxY];
}
