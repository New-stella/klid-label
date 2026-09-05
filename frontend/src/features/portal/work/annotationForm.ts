// 포털 이벤트 어노테이션 폼 ↔ 본문 변환(순수 함수). 컴포넌트 import 없음.
//
// <h3>★ 구조체를 그대로 다룬다 — 키별로 펴지 않는다</h3>
// 본문은 내부 원장과 같은 구조체다. 펴면 산출 문서와 모양이 갈려 내보낼 때마다 재조립이 필요하고
// 중첩 구조가 무너진다.
//
// <h3>★★ 화면이 그리지 않는 값도 잃지 않는다</h3>
// 저장은 <b>구조체 한 벌을 통째로 덮어쓰는</b> 창구다. 그래서 화면이 모르는 키(창구가 나중에 늘린
// 필드·후보별 추가 항목)를 조립 과정에서 흘리면 <b>저장하는 순간 그 값이 사라진다</b> — 오류도
// 경고도 없이 조용히 없어지므로 발견이 늦다. 조립은 <b>불러온 본문 위에 덮어쓰는</b> 방식이다.

// @design API-236 @design API-237

import type {
  CaptionCandidate,
  EventAnnotationPayload,
  EvidenceCandidate,
} from '@/features/label/api/eventAnnotation';
import {
  parseBboxes,
  parseIntegers,
  parseStrings,
} from '@/features/label/components/eventAnnotationForm';
import type { CaptionRow, EvidenceRow } from '@/features/label/components/eventAnnotationShared';

export interface PortalAnnotationForm {
  eventClass: string;
  question: string;
  answer: string;
  captions: CaptionRow[];
  evidences: EvidenceRow[];
}

/** 비어 있으면 키를 지우고, 값이 있으면 얹는다. 빈 문자열을 남기면 「값이 있다」로 읽힌다. */
function setOrDelete(target: Record<string, unknown>, key: string, value: string) {
  if (value.trim() === '') {
    delete target[key];
  } else {
    target[key] = value;
  }
}

/**
 * 폼 → 저장 본문. `base` 는 불러온 본문이며 <b>화면이 모르는 키는 그대로 보존</b>된다.
 */
export function buildPortalAnnotationPayload(
  base: EventAnnotationPayload | null,
  form: PortalAnnotationForm,
): EventAnnotationPayload {
  const result: EventAnnotationPayload = { ...(base ?? { event_class: '' }) };
  result.event_class = form.eventClass;
  setOrDelete(result as unknown as Record<string, unknown>, 'question', form.question);
  setOrDelete(result as unknown as Record<string, unknown>, 'answer', form.answer);

  const caption: Record<string, CaptionCandidate> = {};
  for (const row of form.captions) {
    // 후보별 미지 필드 보존 — 불러온 후보 위에 덮어쓴다.
    const cand: CaptionCandidate = { ...(base?.caption?.[row.key] ?? {}) };
    setOrDelete(cand as unknown as Record<string, unknown>, 'caption_text', row.captionText);
    const cot = row.cot.filter((s) => s.trim() !== '');
    if (cot.length > 0) cand.cot = cot;
    else delete cand.cot;
    if (Object.keys(cand).length > 0) caption[row.key] = cand;
  }
  if (Object.keys(caption).length > 0) result.caption = caption;
  else delete result.caption;

  const evidence: Record<string, EvidenceCandidate> = {};
  for (const row of form.evidences) {
    const cand: EvidenceCandidate = { ...(base?.evidence?.[row.key] ?? {}) };
    setOrDelete(cand as unknown as Record<string, unknown>, 'evidence_text', row.evidenceText);
    assign(cand, 'frame_id', parseIntegers(row.frameId));
    assign(cand, 'obj_id', parseStrings(row.objId));
    assign(cand, 'obj_bbox', parseBboxes(row.objBbox));
    assign(cand, 'obj_label', parseStrings(row.objLabel));
    if (Object.keys(cand).length > 0) evidence[row.key] = cand;
  }
  if (Object.keys(evidence).length > 0) result.evidence = evidence;
  else delete result.evidence;

  return result;
}

function assign(target: object, key: string, value: unknown[]) {
  const bag = target as Record<string, unknown>;
  if (value.length > 0) bag[key] = value;
  else delete bag[key];
}

/**
 * 키 순서에 흔들리지 않는 직렬화 — <b>무변경 판정</b>의 비교값이다.
 *
 * ★판정 단위가 구조체 <b>한 벌 전체</b>인 이유: 이 창구는 항목별로 덮어쓰는 것이 아니라 한 벌을
 * 통째로 덮어쓰므로 항목별 판정 단위가 없다(형제 메타 창구는 항목마다 가른다 — 단위가 다르다).
 */
export function stableStringify(value: unknown): string {
  if (value === null || typeof value !== 'object') return JSON.stringify(value) ?? 'null';
  if (Array.isArray(value)) return `[${value.map(stableStringify).join(',')}]`;
  const entries = Object.entries(value as Record<string, unknown>)
    .filter(([, v]) => v !== undefined)
    .sort(([a], [b]) => a.localeCompare(b));
  return `{${entries.map(([k, v]) => `${JSON.stringify(k)}:${stableStringify(v)}`).join(',')}}`;
}

/** 폼 → 행 배열의 다음 후보 열쇠(기존 최대 인덱스 + 1). */
export { nextKey } from '@/features/label/components/eventAnnotationForm';
