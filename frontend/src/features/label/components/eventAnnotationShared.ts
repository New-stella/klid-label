// Phase 4 — event_annotation 패널 공용 상수·행 타입.
//
// EventAnnotationPanel 과 CaptionCandidateRow/EvidenceCandidateRow 가 공유한다.
// 순환 import 방지를 위해 상수·타입만 이 모듈에 둔다(컴포넌트 import 없음).
//
// 크기 상한은 BE EventAnnotationPayload 의 @Size 계약과 정합(부분 DoS·저장 400 방지):
//   MAX_EVENT_CLASS=100 / MAX_TEXT=4000(question·answer·caption_text·evidence_text)
//   MAX_COT_STEP=2000(cot 각 단계) / MAX_ID=200(obj_id·obj_label 원소).

/** CoT 는 1·2·3단계 고정 배열. */
export const COT_STEPS = 3;
export const COT_LABELS = ['1단계', '2단계', '3단계'];

/** BE @Size 정합 상한(입력 maxLength 힌트). */
export const MAX_EVENT_CLASS = 100;
export const MAX_TEXT = 4000;
export const MAX_COT_STEP = 2000;
export const MAX_ID = 200;

export const INPUT_CLASS =
  'w-full rounded border border-gray-600 bg-gray-800 text-gray-100 text-sm p-1.5 placeholder-gray-500 focus:outline-none focus:ring-1 focus:ring-primary-500';

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
