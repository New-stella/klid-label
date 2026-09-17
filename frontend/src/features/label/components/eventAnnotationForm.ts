// Phase 4 — event_annotation 패널 폼 변환 순수 함수(무상태). EventAnnotationPanel 에서 추출.
//
// 서버 payload ↔ 폼 행(CaptionRow/EvidenceRow) 변환 및 문자열 파싱 유틸.
// 컴포넌트 import 없음(순수 함수) — 단위 테스트·재사용 용이.

import { ApiError } from '@/lib/api/errors';

import {
  normalizeCot,
  type CaptionCandidate,
  type EventAnnotationPayload,
  type EvidenceCandidate,
} from '../api/eventAnnotation';

import { COT_STEPS, type CaptionRow, type EvidenceRow } from './eventAnnotationShared';

/** 후보 배열에서 다음 cN 키(기존 최대 인덱스 + 1). */
export function nextKey(rows: { key: string }[]): string {
  const max = rows.reduce((acc, r) => {
    const m = /^c(\d+)$/.exec(r.key);
    return m ? Math.max(acc, Number(m[1])) : acc;
  }, 0);
  return `c${max + 1}`;
}

/** 콤마 구분 문자열 → 문자열 배열(공백 트림, 빈값 제거). */
export function parseStrings(raw: string): string[] {
  return raw
    .split(',')
    .map((s) => s.trim())
    .filter((s) => s !== '');
}

/** 콤마 구분 문자열 → 정수 배열(frame_id 는 BE List<Integer> — 소수는 절삭). */
export function parseIntegers(raw: string): number[] {
  return raw
    .split(',')
    .map((s) => s.trim())
    .filter((s) => s !== '')
    .map(Number)
    .filter((n) => Number.isFinite(n))
    .map((n) => Math.trunc(n));
}

/** 콤마 구분 문자열 → 숫자 배열(유효 숫자만, 소수 허용 — obj_bbox 용 Double). */
export function parseNumbers(raw: string): number[] {
  return raw
    .split(',')
    .map((s) => s.trim())
    .filter((s) => s !== '')
    .map(Number)
    .filter((n) => Number.isFinite(n));
}

/** 줄바꿈 구분 bbox 목록 → number[][]. 각 줄은 콤마 구분 좌표([x1,y1,x2,y2]). */
export function parseBboxes(raw: string): number[][] {
  return raw
    .split('\n')
    .map((line) => parseNumbers(line))
    .filter((arr) => arr.length > 0);
}

/** 서버 caption(c1..cn) → 폼 CaptionRow[]. cot 는 항상 3단계로 정규화. */
export function toCaptionRows(caption?: Record<string, CaptionCandidate>): CaptionRow[] {
  return Object.entries(caption ?? {}).map(([key, c]) => {
    const cot = normalizeCot(c.cot);
    return {
      key,
      captionText: c.caption_text ?? '',
      cot: Array.from({ length: COT_STEPS }, (_, i) => cot[i] ?? ''),
    };
  });
}

/** 서버 evidence(c1..cn) → 폼 EvidenceRow[]. 리스트는 콤마/줄바꿈 문자열로 직렬화. */
export function toEvidenceRows(evidence?: Record<string, EvidenceCandidate>): EvidenceRow[] {
  return Object.entries(evidence ?? {}).map(([key, e]) => ({
    key,
    evidenceText: e.evidence_text ?? '',
    frameId: (e.frame_id ?? []).join(', '),
    objId: (e.obj_id ?? []).join(', '),
    objBbox: (e.obj_bbox ?? []).map((b) => b.join(',')).join('\n'),
    objLabel: (e.obj_label ?? []).join(', '),
  }));
}

/**
 * 폼 행 → 저장 payload. 값이 비어 있는 항목은 <b>키째 뺀다</b>(BE 계약 그대로).
 *
 * ★순수 함수로 떼어낸 이유는 <b>「바뀌었는가」를 이 함수로 판정</b>하기 때문이다. 창 아래 공통
 * 저장 버튼은 바뀐 칸만 저장하는데, 그 판정을 폼 상태 하나하나 비교로 하면 필드가 늘 때마다
 * 비교식이 함께 늘어 한 곳이 빠진다. 「보낼 것을 만들어 직전에 보낸 것과 비교한다」로 두면
 * 판정 대상이 곧 전송 대상이라 둘이 갈릴 수 없다.
 */
export function buildPayloadFrom(
  eventClass: string,
  question: string,
  answer: string,
  captions: CaptionRow[],
  evidences: EvidenceRow[],
): EventAnnotationPayload {
  const result: EventAnnotationPayload = { event_class: eventClass };
  if (question.trim() !== '') result.question = question;
  if (answer.trim() !== '') result.answer = answer;

  const caption: Record<string, CaptionCandidate> = {};
  for (const row of captions) {
    const cand: CaptionCandidate = {};
    if (row.captionText.trim() !== '') cand.caption_text = row.captionText;
    const cot = row.cot.filter((s) => s.trim() !== '');
    if (cot.length > 0) cand.cot = cot;
    if (Object.keys(cand).length > 0) caption[row.key] = cand;
  }
  if (Object.keys(caption).length > 0) result.caption = caption;

  const evidence: Record<string, EvidenceCandidate> = {};
  for (const row of evidences) {
    const cand: EvidenceCandidate = {};
    if (row.evidenceText.trim() !== '') cand.evidence_text = row.evidenceText;
    const frameId = parseIntegers(row.frameId);
    if (frameId.length > 0) cand.frame_id = frameId;
    const objId = parseStrings(row.objId);
    if (objId.length > 0) cand.obj_id = objId;
    const objBbox = parseBboxes(row.objBbox);
    if (objBbox.length > 0) cand.obj_bbox = objBbox;
    const objLabel = parseStrings(row.objLabel);
    if (objLabel.length > 0) cand.obj_label = objLabel;
    if (Object.keys(cand).length > 0) evidence[row.key] = cand;
  }
  if (Object.keys(evidence).length > 0) result.evidence = evidence;

  return result;
}

/**
 * 변경 판정용 직렬화. <b>키 순서에 흔들리지 않아야</b> 한다 — 같은 내용인데 키 순서만 달라
 * 「저장 안 됨」이 뜨면 사용자는 고치지 않은 것을 고쳤다고 안내받는다.
 */
export function serializePayload(payload: EventAnnotationPayload): string {
  return JSON.stringify(payload, (_key, value: unknown) => {
    if (typeof value !== 'object' || value === null || Array.isArray(value)) return value;
    const sorted: Record<string, unknown> = {};
    for (const k of Object.keys(value as Record<string, unknown>).sort()) {
      sorted[k] = (value as Record<string, unknown>)[k];
    }
    return sorted;
  });
}

/** BE ApiResponse.errorCode/message 를 사용자 안내 문구로 변환(400 원인 노출). */
export function errorMessage(err: unknown, fallback: string): string {
  if (err instanceof ApiError && err.userMessage) return err.userMessage;
  return fallback;
}
