// 이벤트 어노테이션 — 읽기 전용 본문(검수 화면). [@design UI-107] [@design SCREEN-019]
//
// ★입력 컨트롤을 <b>그리지 않는다</b>. 비활성 입력 칸을 두지 않는 이유는 눌리는 모양인데 반응이
//   없으면 검수자가 고장으로 읽기 때문이다(같은 이유로 후보 추가·삭제·「화면에서 지정」도 없다).
// ★근거의 프레임 번호는 누를 수 있는 조작이다 — 누르면 뒤 화면이 그 프레임으로 이동하고 창은
//   접힌다. 이 영상에 없는 번호는 이동하지 않고 알림으로만 알린다(판정은 상위가 한다).
// 보안: 값은 텍스트 노드로만 출력해 자동 escape 된다(CWE-79).

import { cn } from '@/lib/cn';

import { candidateName, sortCandidateKeys } from '../annotationSummary';
import { normalizeCot, type EventAnnotationPayload } from '../api/eventAnnotation';

import { AnnotationField } from './AnnotationField';
import {
  EVIDENCE_FIELD_LABEL,
  FIELD_HELP,
  NOT_FILLED_LABEL,
  NO_EVIDENCE_TEXT,
} from './annotationWording';
import { COT_LABELS, COT_STEPS } from './eventAnnotationShared';

export interface EventAnnotationReadOnlyProps {
  payload: EventAnnotationPayload | undefined;
  helpVisible?: boolean;
  /** 이벤트 분류 이름(코드로 찾은 값). 모르는 코드면 undefined — 코드만 보인다. */
  eventTypeName?: string;
  /** 이 영상이 실제로 가진 프레임 번호들 — 없는 번호를 흐리게 보이기 위해서만 쓴다. */
  availableFrameIds?: ReadonlySet<number>;
  onJumpToFrame?: (frameId: number, evidenceKey: string) => void;
}

function ReadOnlyValue({ value, testId }: { value: string | null | undefined; testId?: string }) {
  const filled = typeof value === 'string' && value.trim() !== '';
  return (
    <p
      data-testid={testId}
      className={cn(
        'whitespace-pre-wrap break-words text-body-md',
        filled ? 'text-gray-900' : 'text-gray-500',
      )}
    >
      {filled ? value : NOT_FILLED_LABEL}
    </p>
  );
}

export function EventAnnotationReadOnly({
  payload,
  helpVisible = true,
  eventTypeName,
  availableFrameIds,
  onJumpToFrame,
}: EventAnnotationReadOnlyProps) {
  const captionKeys = sortCandidateKeys(Object.keys(payload?.caption ?? {}));
  const evidenceKeys = sortCandidateKeys(Object.keys(payload?.evidence ?? {}));
  const eventClass = payload?.event_class ?? '';

  return (
    <div data-testid="event-annotation-readonly">
      <AnnotationField label="이벤트 분류" help={FIELD_HELP.eventClass} helpVisible={helpVisible}>
        {eventClass.trim() === '' ? (
          <ReadOnlyValue value="" />
        ) : (
          <p className="text-body-md text-gray-900" data-testid="ea-event-class-readonly">
            {eventTypeName !== undefined && (
              <span className="font-bold">{eventTypeName} </span>
            )}
            <span className={eventTypeName === undefined ? 'text-gray-900' : 'text-gray-500'}>
              {eventClass}
            </span>
          </p>
        )}
      </AnnotationField>

      <AnnotationField label="질의" help={FIELD_HELP.question} helpVisible={helpVisible}>
        <ReadOnlyValue value={payload?.question} />
      </AnnotationField>

      <AnnotationField label="답변" help={FIELD_HELP.answer} helpVisible={helpVisible}>
        <ReadOnlyValue value={payload?.answer} />
      </AnnotationField>

      <h3 className="mb-1 mt-4 text-caption font-bold text-gray-700">캡션</h3>
      {helpVisible && <p className="mb-2 text-caption text-gray-500">{FIELD_HELP.captionSection}</p>}
      {captionKeys.length === 0 ? (
        <ReadOnlyValue value="" testId="ea-caption-empty" />
      ) : (
        captionKeys.map((key) => {
          const cand = payload?.caption?.[key];
          const cot = normalizeCot(cand?.cot);
          const name = candidateName('캡션', key);
          return (
            <div key={key} className="mb-2.5 rounded-md border border-gray-200 p-2.5">
              <span className="mb-2 block text-caption font-semibold text-gray-600">
                {name}
                <span className="ml-1.5 font-normal text-gray-400">{key}</span>
              </span>
              <AnnotationField
                label="캡션 문장"
                help={FIELD_HELP.captionText}
                helpVisible={helpVisible}
                after={
                  <p className="mt-1 text-caption text-gray-500">
                    {FIELD_HELP.captionMarkdownNote}
                  </p>
                }
              >
                {/* 받은 원문 그대로 보인다 — 굵게·목록 기호를 어떻게 보일지는 사업자 회신 대기라
                    임의로 해석해 그리지 않는다(해석하면 원문과 다른 것을 보여주게 된다). */}
                <ReadOnlyValue value={cand?.caption_text} testId={`ea-caption-text-ro-${key}`} />
              </AnnotationField>
              <AnnotationField label="사고 단계" help={FIELD_HELP.cot} helpVisible={helpVisible}>
                <ol className="space-y-1">
                  {Array.from({ length: COT_STEPS }, (_, i) => (
                    <li key={i} className="flex items-start gap-2">
                      <span className="w-12 shrink-0 text-caption text-gray-600">
                        {COT_LABELS[i]}
                      </span>
                      <span className="min-w-0 flex-1">
                        <ReadOnlyValue value={cot[i]} testId={`ea-caption-cot-ro-${key}-${i}`} />
                      </span>
                    </li>
                  ))}
                </ol>
              </AnnotationField>
            </div>
          );
        })
      )}

      <h3 className="mb-1 mt-4 text-caption font-bold text-gray-700">근거</h3>
      {helpVisible && (
        <p className="mb-2 text-caption text-gray-500">{FIELD_HELP.evidenceSection}</p>
      )}
      {evidenceKeys.length === 0 ? (
        <p
          data-testid="ea-evidence-empty"
          className="rounded-md border border-dashed border-gray-200 p-2.5 text-body-md text-gray-500"
        >
          {NO_EVIDENCE_TEXT.readOnly}
        </p>
      ) : (
        evidenceKeys.map((key) => {
          const cand = payload?.evidence?.[key];
          const name = candidateName('근거', key);
          const frameIds = cand?.frame_id ?? [];
          const objects = (cand?.obj_id ?? []).map(
            (id, i) => `${id}${(cand?.obj_label ?? [])[i] !== undefined ? ` · ${(cand?.obj_label ?? [])[i]}` : ''}`,
          );
          const bbox = (cand?.obj_bbox ?? []).map((b) => b.join(', ')).join('\n');
          return (
            <div key={key} className="mb-2.5 rounded-md border border-gray-200 p-2.5">
              <span className="mb-2 block text-caption font-semibold text-gray-600">
                {name}
                <span className="ml-1.5 font-normal text-gray-400">{key}</span>
              </span>
              <AnnotationField
                label={EVIDENCE_FIELD_LABEL.evidenceText}
                help={FIELD_HELP.evidenceText}
                helpVisible={helpVisible}
              >
                <ReadOnlyValue value={cand?.evidence_text} />
              </AnnotationField>
              {/* ★칸 이름 「프레임」이 곧 <b>칩들 앞에 한 번만 두는 접두</b>다 — 칩 안에
                  「프레임」을 되풀이해 적지 않는다(사양: 접두를 칩마다 되풀이하지 않는다). */}
              <AnnotationField
                label={EVIDENCE_FIELD_LABEL.frameId}
                help={FIELD_HELP.frameIdReadOnly}
                helpVisible={helpVisible}
              >
                {frameIds.length === 0 ? (
                  <ReadOnlyValue value="" />
                ) : (
                  <p className="flex flex-wrap gap-1.5">
                    {frameIds.map((frameId) => {
                      const missing =
                        availableFrameIds !== undefined && !availableFrameIds.has(frameId);
                      return (
                        <button
                          key={frameId}
                          type="button"
                          data-testid={`ea-evidence-frame-link-${key}-${frameId}`}
                          onClick={() => onJumpToFrame?.(frameId, key)}
                          aria-label={
                            missing
                              ? `${name} 프레임 ${frameId} — 이 영상에 없음`
                              : `${name} 프레임 ${frameId} 으로 이동`
                          }
                          className={cn(
                            'inline-flex items-center rounded-full border px-2 py-0.5 text-body-md font-semibold',
                            missing
                              // 연한 배경 위 글자는 60단 이상이어야 AA(4.5:1)를 넘는다
                              // (gray-500 은 gray-50 위에서 4.13:1).
                              ? 'border-gray-300 bg-gray-50 text-gray-600'
                              : 'border-primary-400 bg-primary-50 text-primary-600 hover:bg-primary-100',
                          )}
                        >
                          {frameId}
                          {/* ★없는 번호는 <b>글자로도</b> 말한다 — 흐린 색만으로 가르면 색을
                              구분하지 못하는 사용자에게는 눌러 보기 전까지 같은 칩이다
                              (시안 `.framelink.bad` 도 사유를 글자로 적는다). */}
                          {missing ? (
                            <span className="ml-1 font-normal"> · 이 영상에 없음</span>
                          ) : (
                            /* 이동 표식 — 보조 표기다. 이동할 수 있다는 사실은 위 aria-label 이
                               글자로도 말하므로 표식만으로 정보를 전달하지 않는다. 없는 번호에는
                               두지 않아 누를 수 있는 칩과 구분된다.
                               ⚠ 화살표(U+2197)는 이모지 가드의 글리프 범위 밖이다 — 그 가드는
                                 화살표를 산문 기호로 보아 일부러 제외한다(`GLYPH_RANGES` 주석). */
                            <span aria-hidden="true" className="ml-1 font-normal">
                              ↗
                            </span>
                          )}
                        </button>
                      );
                    })}
                  </p>
                )}
              </AnnotationField>
              <AnnotationField
                label={EVIDENCE_FIELD_LABEL.objectReadOnly}
                help={FIELD_HELP.objectReadOnly}
                helpVisible={helpVisible}
              >
                <ReadOnlyValue value={objects.join(', ')} />
              </AnnotationField>
              <AnnotationField
                label={EVIDENCE_FIELD_LABEL.objBbox}
                help={FIELD_HELP.objBboxReadOnly}
                helpVisible={helpVisible}
              >
                <ReadOnlyValue value={bbox} />
              </AnnotationField>
            </div>
          );
        })
      )}
    </div>
  );
}
