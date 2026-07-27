// Phase 4 — event_annotation 패널 폼 변환 순수 함수(무상태). EventAnnotationPanel 에서 추출.
//
// 서버 payload ↔ 폼 행(CaptionRow/EvidenceRow) 변환 및 문자열 파싱 유틸.
// 컴포넌트 import 없음(순수 함수) — 단위 테스트·재사용 용이.

import { ApiError } from '@/lib/api/errors';

import { normalizeCot, type CaptionCandidate, type EvidenceCandidate } from '../api/eventAnnotation';

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

/** BE ApiResponse.errorCode/message 를 사용자 안내 문구로 변환(400 원인 노출). */
export function errorMessage(err: unknown, fallback: string): string {
  if (err instanceof ApiError && err.userMessage) return err.userMessage;
  return fallback;
}
