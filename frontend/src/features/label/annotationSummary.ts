// 요약 카드가 보여줄 값 도출 — 순수 함수. [@design UI-157]
//
// 화면이 아니라 여기에 두는 이유: 같은 요약을 라벨링 메타 탭과 검수 메타 탭 두 곳이 그린다.
// 두 화면이 각자 계산하면 「아직 채우지 않은 항목」의 판정이 갈려, 같은 영상에서 서로 다른
// 안내가 뜬다.

import { COT_STEPS } from './components/eventAnnotationShared';
import type { CaptionCandidate, EventAnnotationPayload } from './api/eventAnnotation';
import { normalizeCot } from './api/eventAnnotation';

/** 값이 실제로 채워졌는가 — 공백만 있는 값은 채운 것이 아니다. */
function filled(value: string | null | undefined): boolean {
  return typeof value === 'string' && value.trim() !== '';
}

/** 후보 키를 저장 키의 숫자 오름차순으로 정렬한다(c2 가 c10 앞에 오도록 숫자로 읽는다). */
export function sortCandidateKeys(keys: string[]): string[] {
  return [...keys].sort((a, b) => {
    const na = /^c(\d+)$/.exec(a);
    const nb = /^c(\d+)$/.exec(b);
    if (na && nb) return Number(na[1]) - Number(nb[1]);
    if (na) return -1;
    if (nb) return 1;
    return a.localeCompare(b);
  });
}

/**
 * 후보 이름 — 「캡션 1」·「근거 1」.
 *
 * ★번호는 <b>저장 키의 숫자</b>다. 목록 순번으로 다시 매기지 않는다 — c1 을 지우고 c2 만 남은
 * 화면에서 그것을 「근거 1」로 부르면, 저장된 키(c2)와 화면의 이름이 갈려 사람이 가리키는 것과
 * 저장된 것이 달라진다.
 */
export function candidateName(kind: '캡션' | '근거', key: string): string {
  const m = /^c(\d+)$/.exec(key);
  return `${kind} ${m ? m[1] : key}`;
}

/** 후보 키의 숫자 — 띠 머리(「근거 1 지정 중」)가 쓴다. 숫자 형태가 아니면 null. */
export function candidateNumber(key: string): number | null {
  const m = /^c(\d+)$/.exec(key);
  return m ? Number(m[1]) : null;
}

/**
 * 이벤트 분류 코드 → 이름. 목록에 없으면 <b>undefined</b>이며 그때 화면은 코드만 보인다.
 *
 * ⚠ 코드를 이름 자리에 채워 넣지 않는다 — 그렇게 하면 사업자 내부 코드가 이름인 것처럼 보이고,
 * 그 화면을 보고 만든 다음 판단이 코드 체계를 이름으로 오해한다.
 */
export function eventTypeNameOf(
  types: ReadonlyArray<{ vrfcEvntTypeCd: string; vrfcEvntTypeNm: string }> | undefined,
  code: string | null | undefined,
): string | undefined {
  if (typeof code !== 'string' || code.trim() === '') return undefined;
  return types?.find((t) => t.vrfcEvntTypeCd === code.trim())?.vrfcEvntTypeNm;
}

/** 사고 단계 중 비어 있는 단계의 번호(1-base). 캡션 후보 하나를 기준으로 본다. */
function missingCotSteps(candidate: CaptionCandidate | undefined): number[] {
  const cot = normalizeCot(candidate?.cot);
  const missing: number[] = [];
  for (let i = 0; i < COT_STEPS; i += 1) {
    if (!filled(cot[i])) missing.push(i + 1);
  }
  return missing;
}

export interface UnfilledInput {
  /** 영상 분석 설명(시계열 메타 편집 슬롯)의 값들. 하나라도 채워져 있으면 채운 것으로 본다. */
  descriptions: string[];
  payload: EventAnnotationPayload | undefined;
}

/**
 * 「아직 채우지 않은 항목」 목록 — 사람이 읽는 이름으로, 화면에 보이는 순서대로.
 *
 * 사고 단계는 「사고 2·3단계」처럼 <b>묶어서</b> 적는다. 단계마다 한 줄씩 적으면 목록이 길어져
 * 정작 무엇이 비었는지가 묻힌다.
 */
export function unfilledItems({ descriptions, payload }: UnfilledInput): string[] {
  const items: string[] = [];
  if (!descriptions.some(filled)) items.push('영상 분석 설명');
  if (!filled(payload?.event_class)) items.push('이벤트 분류');
  if (!filled(payload?.question)) items.push('질의');
  if (!filled(payload?.answer)) items.push('답변');

  const captionKeys = sortCandidateKeys(Object.keys(payload?.caption ?? {}));
  const firstCaption = captionKeys.length > 0 ? payload?.caption?.[captionKeys[0]] : undefined;
  if (!captionKeys.some((k) => filled(payload?.caption?.[k]?.caption_text))) {
    items.push('캡션 문장');
  }
  const missingSteps = missingCotSteps(firstCaption);
  if (missingSteps.length > 0) items.push(`사고 ${missingSteps.join('·')}단계`);

  const evidenceKeys = Object.keys(payload?.evidence ?? {});
  const hasEvidence = evidenceKeys.some((k) => {
    const cand = payload?.evidence?.[k];
    return (
      filled(cand?.evidence_text) ||
      (cand?.frame_id ?? []).length > 0 ||
      (cand?.obj_id ?? []).length > 0
    );
  });
  if (!hasEvidence) items.push('근거');

  return items;
}

/**
 * 영상 분석 설명의 <b>첫 줄</b> — 요약 카드가 보여줄 한 조각.
 *
 * 여러 슬롯이 있으면 값이 있는 첫 슬롯을 쓴다. 전부 비었으면 null 이며, 그때 카드는 값 대신
 * 「아직 채우지 않은 항목」 쪽에서 그 사실을 말한다(빈 문자열을 그리지 않는다).
 */
export function descriptionFirstLine(descriptions: string[]): string | null {
  for (const value of descriptions) {
    if (!filled(value)) continue;
    const line = value.split('\n').find((l) => l.trim() !== '');
    if (line !== undefined) return line.trim();
  }
  return null;
}
