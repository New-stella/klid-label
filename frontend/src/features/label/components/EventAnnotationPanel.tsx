// 이벤트 어노테이션 칸 — 「영상 분석 설명 · 이벤트 어노테이션」 창의 오른쪽 칸.
// [@design UI-107] [@design SCREEN-005] [@design SCREEN-019] [@design API-132]
//
// - useEventAnnotation(rawSn) 로 자동 생성 기본값을 <b>영상당 1회</b> 프리필 → 사람이 고친다
// - caption/evidence 후보(c1..cn) 추가·삭제, caption 은 사고 단계 3칸
// - REVIEWER(내부 채널)면 검토 승인/반려 + 검토 상태 표시
//
// ★저장 버튼이 이 칸에 없다 — 창 아래 공통 「저장」 하나가 영상 분석 설명과 함께 저장한다.
//   그 버튼이 「바뀐 칸만」 보내려면 이 칸이 <b>바뀌었는지</b>와 <b>저장하는 법</b>을 밖에 내줘야
//   하며, 그 창구가 {@link EventAnnotationPanelHandle} 다.
// ★근거 「화면에서 지정」은 이 칸이 요청만 하고 창이 수행한다 — 담은 값은 applyPicked 로 돌아와
//   기존 「현재 프레임 추가」·「선택 객체 추가」와 <b>같은 규칙</b>으로 병합된다(중복 방지 포함).
//
// 보안(저장형 XSS 방어): 모든 값은 input/textarea value 또는 텍스트 노드로만 바인딩 — React 기본
//   escape. dangerouslySetInnerHTML 미사용. maxLength 로 입력 크기 제한(BE @Size 정합).
// 불변성: 상태 갱신은 항상 새 배열/객체 생성(spread) — 직접 mutation 금지.

import { forwardRef, useEffect, useImperativeHandle, useMemo, useRef, useState } from 'react';

import { Button } from '@/components/common/Button';
import { Textarea } from '@/components/common/Textarea';
import { Role } from '@/lib/api/types';
import { roleSatisfies } from '@/lib/authz';
import { useAuthStore } from '@/stores/useAuthStore';
import { useLabelStore } from '@/stores/useLabelStore';
import { useUiStore } from '@/stores/useUiStore';

import { eventTypeNameOf, sortCandidateKeys } from '../annotationSummary';
import { useEventAnnotation } from '../hooks/useEventAnnotation';
import { useEventAnnotationReview } from '../hooks/useEventAnnotationReview';
import { useUpdateEventAnnotation } from '../hooks/useUpdateEventAnnotation';
import type { EventAnnotationPayload } from '../api/eventAnnotation';
import type { Label } from '../types';

import { AnnotationColumn, type AnnotationColumnHandle } from './AnnotationColumn';
import { AnnotationField } from './AnnotationField';
import {
  ADD_CAPTION_LABEL,
  ADD_EVIDENCE_LABEL,
  ANNOTATION_COLUMN_DESCRIPTION,
  ANNOTATION_COLUMN_TITLE,
  FIELD_HELP,
  FIELD_PLACEHOLDER,
  NO_EVIDENCE_TEXT,
  reviewStatusLabel,
} from './annotationWording';
import { CaptionCandidateRow } from './CaptionCandidateRow';
import { EventAnnotationReadOnly } from './EventAnnotationReadOnly';
import { EvidenceCandidateRow } from './EvidenceCandidateRow';
import {
  buildPayloadFrom,
  errorMessage,
  nextKey,
  serializePayload,
  toCaptionRows,
  toEvidenceRows,
} from './eventAnnotationForm';
import {
  bboxFromShape,
  COT_STEPS,
  INPUT_CLASS,
  MAX_EVENT_CLASS,
  MAX_TEXT,
  type CaptionRow,
  type EvidenceRow,
} from './eventAnnotationShared';

/** 이벤트 분류 이름 조달원 — 영상 상세 응답의 전체 검증 이벤트 유형 목록(API-043). */
export interface EventTypeOption {
  vrfcEvntTypeCd: string;
  vrfcEvntTypeNm: string;
}

/** 「화면에서 지정」으로 담은 값 — 프레임 번호와 캔버스 라벨. */
export interface PickedEvidence {
  frames: number[];
  labels: Label[];
}

export interface EventAnnotationPanelHandle extends AnnotationColumnHandle {
  /** 지정 모드에서 담은 값을 그 근거 후보에 병합한다(기존 담기 규칙 그대로). */
  applyPicked: (key: string, picked: PickedEvidence) => void;
}

export interface EventAnnotationPanelProps {
  rawSn: number | undefined;
  /** 현재 프레임 SRC_SN — 근거의 프레임 번호 담기 보조. */
  currentSrcSn?: number;
  /** 읽기 전용(검수) — 입력·후보 추가/삭제·지정 컨트롤을 그리지 않는다. */
  readOnly?: boolean;
  helpVisible?: boolean;
  /** 이벤트 분류 이름을 찾을 목록. 목록에 없는 코드는 코드만 보인다. */
  eventTypes?: EventTypeOption[];
  onDirtyChange?: (dirty: boolean) => void;
  /** 「화면에서 지정」 요청 — 넘기지 않으면 그 버튼을 그리지 않는다. */
  onRequestPick?: (key: string) => void;
  /** 방금 지정으로 담은 값 안내(어느 후보에 무엇이 담겼는지). */
  pickedNotice?: { key: string; text: string } | null;
  /** 읽기 전용 — 근거 프레임 번호를 눌렀을 때. */
  onJumpToFrame?: (frameId: number, evidenceKey: string) => void;
  availableFrameIds?: ReadonlySet<number>;
}

/** 검토(승인/반려) 가능한 상태 — 그 외(APPROVED/REJECTED/미생성)엔 버튼을 노출하지 않는다. */
const REVIEWABLE_STATUSES = new Set(['AUTO_GENERATED', 'PENDING']);

/** 콤마 append(단일값 필드): 기존이 비면 그대로, 있으면 ', ' 로 이어붙임. */
function appendComma(existing: string, value: string): string {
  return existing.trim() === '' ? value : `${existing}, ${value}`;
}

/** 개행 append(객체 박스 좌표는 한 줄에 하나): 기존이 비면 그대로, 있으면 '\n' 으로 이어붙임. */
function appendNewline(existing: string, value: string): string {
  return existing.trim() === '' ? value : `${existing}\n${value}`;
}

/** 콤마 분해(trim·빈값 제외) 후 value(trim)가 이미 있는지. */
function commaHas(existing: string, value: string): boolean {
  const target = value.trim();
  return existing
    .split(',')
    .map((s) => s.trim())
    .filter((s) => s !== '')
    .includes(target);
}

/** 콤마 append + 중복 제거: value 가 이미 존재하면 existing 그대로 반환. */
function appendCommaUnique(existing: string, value: string): string {
  return commaHas(existing, value) ? existing : appendComma(existing, value);
}

/**
 * 근거 행 하나에 <b>캔버스 라벨</b>을 병합한다 — 담기 규칙의 단일 지점.
 *
 * - 객체 번호: 트랙 ID → 서버 ID → 클라이언트 ID 순
 * - 객체 분류 이름: 라벨명 / 객체 박스 좌표: 외접 박스를 정수로 반올림
 * - 객체 번호가 이미 있으면 객체 세 필드를 <b>세트로</b> 건너뛴다(병렬 배열 정렬 보존)
 * - 프레임 번호는 독립 dedup(같은 프레임의 다른 객체를 담아도 1개만 남는다)
 */
function mergeLabelIntoRow(row: EvidenceRow, label: Label, frameId: number | undefined): EvidenceRow {
  const objIdVal = label.trackId ?? (label.serverId != null ? String(label.serverId) : label.id);
  const bbox = bboxFromShape(label.shape);
  const bboxLine = bbox ? bbox.map((v) => Math.round(v)).join(',') : null;
  const next: EvidenceRow = { ...row };
  if (!commaHas(row.objId, objIdVal)) {
    next.objId = appendComma(row.objId, objIdVal);
    next.objLabel = appendComma(row.objLabel, label.className);
    if (bboxLine !== null) next.objBbox = appendNewline(row.objBbox, bboxLine);
  }
  if (frameId !== undefined) {
    next.frameId = appendCommaUnique(next.frameId, String(frameId));
  }
  return next;
}

export const EventAnnotationPanel = forwardRef<
  EventAnnotationPanelHandle,
  EventAnnotationPanelProps
>(function EventAnnotationPanel(
  {
    rawSn,
    currentSrcSn,
    readOnly = false,
    helpVisible = true,
    eventTypes,
    onDirtyChange,
    onRequestPick,
    pickedNotice,
    onJumpToFrame,
    availableFrameIds,
  },
  ref,
) {
  const pushToast = useUiStore((s) => s.pushToast);
  const role = useAuthStore((s) => s.claims?.role);
  const channel = useAuthStore((s) => s.claims?.channel);
  // 검수자 자리 — 관리자는 계층으로 함께 들어온다. 채널 축은 별개라 그대로 둔다.
  const isReviewer = roleSatisfies(role, Role.REVIEWER) && channel === 'INTERNAL';

  // 캔버스 선택 객체(근거 자동연결용) — 필요한 값만 셀렉터로 구독(스토어 전체 구독 금지).
  const selectedLabelId = useLabelStore((s) => s.selectedLabelId);
  const labels = useLabelStore((s) => s.labels);
  const selectedLabel = useMemo(
    () => (selectedLabelId ? (labels.find((l) => l.id === selectedLabelId) ?? null) : null),
    [selectedLabelId, labels],
  );

  const { data } = useEventAnnotation(rawSn);
  const update = useUpdateEventAnnotation(rawSn);
  const review = useEventAnnotationReview(rawSn, {
    onApproveSuccess: () => pushToast({ variant: 'success', message: '검토를 승인했습니다.' }),
    onApproveError: (err) =>
      pushToast({ variant: 'error', message: errorMessage(err, '승인에 실패했습니다.') }),
    onRejectSuccess: () => {
      setRejectReason('');
      pushToast({ variant: 'success', message: '검토를 반려했습니다.' });
    },
    onRejectError: (err) =>
      pushToast({ variant: 'error', message: errorMessage(err, '반려에 실패했습니다.') }),
  });

  const [eventClass, setEventClass] = useState('');
  const [question, setQuestion] = useState('');
  const [answer, setAnswer] = useState('');
  const [captions, setCaptions] = useState<CaptionRow[]>([]);
  const [evidences, setEvidences] = useState<EvidenceRow[]>([]);
  const [rejectReason, setRejectReason] = useState('');
  const [failed, setFailed] = useState(false);

  // 서버 값(자동 생성 기본값)을 폼에 프리필 — 영상(rawSn)당 최초 1회만.
  // 이후 서버 재조회(invalidate)로 payload 가 다시 들어와도 편집 중인 폼을 덮어쓰지 않는다.
  const payload = data?.payload;
  const prefilledRawSnRef = useRef<number | undefined>(undefined);
  /** 「바뀌었는가」의 기준선 — 프리필 시점과 저장 성공 시점에 다시 잡는다. */
  const baselineRef = useRef<string>(serializePayload({ event_class: '' }));
  const [baseline, setBaseline] = useState<string>(baselineRef.current);
  useEffect(() => {
    if (!payload || rawSn === undefined) return;
    if (prefilledRawSnRef.current === rawSn) return;
    prefilledRawSnRef.current = rawSn;
    const nextCaptions = toCaptionRows(payload.caption);
    const nextEvidences = toEvidenceRows(payload.evidence);
    setEventClass(payload.event_class ?? '');
    setQuestion(payload.question ?? '');
    setAnswer(payload.answer ?? '');
    setCaptions(nextCaptions);
    setEvidences(nextEvidences);
    // 폼 상태로 다시 만든 payload 를 기준선으로 삼는다 — 서버 payload 를 그대로 쓰면 폼이
    // 버리는 빈 값(공백만 있는 필드) 때문에 열자마자 「저장 안 됨」이 뜬다.
    const next = serializePayload(
      buildPayloadFrom(
        payload.event_class ?? '',
        payload.question ?? '',
        payload.answer ?? '',
        nextCaptions,
        nextEvidences,
      ),
    );
    baselineRef.current = next;
    setBaseline(next);
  }, [payload, rawSn]);

  const reviewStatus = data?.reviewStatus ?? null;
  const canReview =
    !readOnly && isReviewer && reviewStatus !== null && REVIEWABLE_STATUSES.has(reviewStatus);

  const currentPayload = useMemo<EventAnnotationPayload>(
    () => buildPayloadFrom(eventClass, question, answer, captions, evidences),
    [eventClass, question, answer, captions, evidences],
  );
  const dirty = !readOnly && serializePayload(currentPayload) !== baseline;

  useEffect(() => {
    onDirtyChange?.(dirty);
  }, [dirty, onDirtyChange]);

  useEffect(() => {
    if (!dirty) setFailed(false);
  }, [dirty]);

  useImperativeHandle(
    ref,
    () => ({
      save: async () => {
        if (readOnly || !dirty) return true;
        // 이벤트 분류는 필수다 — 비어 있으면 BE 400 이므로 보내기 전에 막고 사유를 알린다.
        if (eventClass.trim() === '') {
          setFailed(true);
          pushToast({ variant: 'error', message: '이벤트 분류를 입력해 주세요.' });
          return false;
        }
        try {
          const sent = serializePayload(currentPayload);
          await update.mutateAsync(currentPayload);
          baselineRef.current = sent;
          setBaseline(sent);
          setFailed(false);
          return true;
        } catch {
          setFailed(true);
          return false;
        }
      },
      applyPicked: (key, picked) => {
        setEvidences((prev) =>
          prev.map((r) => {
            if (r.key !== key) return r;
            let next = r;
            for (const frameId of picked.frames) {
              next = { ...next, frameId: appendCommaUnique(next.frameId, String(frameId)) };
            }
            for (const label of picked.labels) {
              next = mergeLabelIntoRow(next, label, undefined);
            }
            return next;
          }),
        );
      },
    }),
    [readOnly, dirty, eventClass, currentPayload, update, pushToast],
  );

  // --- 불변 갱신 헬퍼 ---
  const addCaption = () =>
    setCaptions((prev) => [
      ...prev,
      { key: nextKey(prev), captionText: '', cot: Array.from({ length: COT_STEPS }, () => '') },
    ]);
  const removeCaption = (key: string) => setCaptions((prev) => prev.filter((r) => r.key !== key));
  const updateCaptionText = (key: string, value: string) =>
    setCaptions((prev) => prev.map((r) => (r.key === key ? { ...r, captionText: value } : r)));
  const updateCot = (key: string, idx: number, value: string) =>
    setCaptions((prev) =>
      prev.map((r) =>
        r.key === key ? { ...r, cot: r.cot.map((c, i) => (i === idx ? value : c)) } : r,
      ),
    );

  const addEvidence = () =>
    setEvidences((prev) => [
      ...prev,
      { key: nextKey(prev), evidenceText: '', frameId: '', objId: '', objBbox: '', objLabel: '' },
    ]);
  const removeEvidence = (key: string) => setEvidences((prev) => prev.filter((r) => r.key !== key));
  const updateEvidenceField = (key: string, field: keyof Omit<EvidenceRow, 'key'>, value: string) =>
    setEvidences((prev) => prev.map((r) => (r.key === key ? { ...r, [field]: value } : r)));
  const appendCurrentFrame = (key: string) => {
    if (currentSrcSn === undefined) return;
    setEvidences((prev) =>
      prev.map((r) =>
        r.key === key ? { ...r, frameId: appendCommaUnique(r.frameId, String(currentSrcSn)) } : r,
      ),
    );
  };
  const appendSelectedObject = (key: string) => {
    const label = selectedLabel;
    if (!label) return;
    setEvidences((prev) =>
      prev.map((r) => (r.key === key ? mergeLabelIntoRow(r, label, currentSrcSn) : r)),
    );
  };

  const handleReject = () => {
    if (rejectReason.trim() === '' || review.reject.isPending) return;
    review.reject.mutate(rejectReason.trim());
  };

  const eventTypeName = useMemo(
    () => eventTypeNameOf(eventTypes, eventClass),
    [eventTypes, eventClass],
  );

  const description = readOnly
    ? ANNOTATION_COLUMN_DESCRIPTION.readOnly
    : ANNOTATION_COLUMN_DESCRIPTION.editable;

  const orderedCaptions = useMemo(() => {
    const order = sortCandidateKeys(captions.map((c) => c.key));
    return order.flatMap((k) => captions.filter((c) => c.key === k));
  }, [captions]);
  const orderedEvidences = useMemo(() => {
    const order = sortCandidateKeys(evidences.map((e) => e.key));
    return order.flatMap((k) => evidences.filter((e) => e.key === k));
  }, [evidences]);

  return (
    <AnnotationColumn
      title={ANNOTATION_COLUMN_TITLE}
      description={description}
      dirty={dirty}
      failed={failed}
      data-testid="annotation-column"
    >
      <div data-testid="event-annotation-panel">
        {readOnly ? (
          <EventAnnotationReadOnly
            payload={payload}
            helpVisible={helpVisible}
            eventTypeName={eventTypeNameOf(eventTypes, payload?.event_class)}
            availableFrameIds={availableFrameIds}
            onJumpToFrame={onJumpToFrame}
          />
        ) : (
          <>
            {/* 검토 상태 표시 — 중립 한글 라벨. 검수 화면(읽기 전용)에는 두지 않는다. */}
            {reviewStatus !== null && (
              <div className="mb-2 flex items-center gap-1 text-caption">
                <span className="text-gray-500">검토 상태</span>
                <span
                  data-testid="ea-review-status"
                  className="rounded bg-gray-100 px-1.5 py-0.5 text-gray-700"
                >
                  {reviewStatusLabel(reviewStatus)}
                </span>
              </div>
            )}

            <AnnotationField
              label="이벤트 분류"
              required
              help={FIELD_HELP.eventClass}
              helpVisible={helpVisible}
              htmlFor="ea-event-class"
            >
              <div className="flex items-center gap-2">
                <input
                  id="ea-event-class"
                  data-testid="ea-event-class"
                  type="text"
                  value={eventClass}
                  onChange={(e) => setEventClass(e.target.value)}
                  maxLength={MAX_EVENT_CLASS}
                  aria-label="이벤트 분류"
                  className={INPUT_CLASS}
                />
                {/* 이름은 코드로 찾은 값이다 — 모르는 코드면 아무것도 덧붙이지 않는다(코드만 보인다). */}
                {eventTypeName !== undefined && (
                  <span
                    data-testid="ea-event-class-name"
                    className="shrink-0 whitespace-nowrap text-body-md font-bold text-gray-900"
                  >
                    {eventTypeName}
                  </span>
                )}
              </div>
            </AnnotationField>

            <AnnotationField
              label="질의"
              help={FIELD_HELP.question}
              helpVisible={helpVisible}
              htmlFor="ea-question"
            >
              <Textarea
                id="ea-question"
                data-testid="ea-question"
                value={question}
                onChange={(e) => setQuestion(e.target.value)}
                maxLength={MAX_TEXT}
                aria-label="질의"
                className="min-h-[72px] resize-y text-body-md"
              />
            </AnnotationField>

            <AnnotationField
              label="답변"
              help={FIELD_HELP.answer}
              helpVisible={helpVisible}
              htmlFor="ea-answer"
            >
              <Textarea
                id="ea-answer"
                data-testid="ea-answer"
                value={answer}
                onChange={(e) => setAnswer(e.target.value)}
                maxLength={MAX_TEXT}
                aria-label="답변"
                placeholder={FIELD_PLACEHOLDER.answer}
                className="min-h-[72px] resize-y text-body-md"
              />
            </AnnotationField>

            <div className="mb-2 mt-4 flex items-start justify-between gap-2">
              <div>
                <h3 className="text-caption font-bold text-gray-700">캡션</h3>
                {helpVisible && (
                  <p className="mt-0.5 text-caption text-gray-500">{FIELD_HELP.captionSection}</p>
                )}
              </div>
              <button
                type="button"
                data-testid="ea-add-caption"
                onClick={addCaption}
                className="shrink-0 text-caption font-semibold text-primary-600 hover:text-primary-700"
              >
                {ADD_CAPTION_LABEL}
              </button>
            </div>
            {orderedCaptions.map((row) => (
              <CaptionCandidateRow
                key={row.key}
                row={row}
                helpVisible={helpVisible}
                onRemove={removeCaption}
                onCaptionTextChange={updateCaptionText}
                onCotChange={updateCot}
              />
            ))}

            <div className="mb-2 mt-4 flex items-start justify-between gap-2">
              <div>
                <h3 className="text-caption font-bold text-gray-700">근거</h3>
                {helpVisible && (
                  <p className="mt-0.5 text-caption text-gray-500">{FIELD_HELP.evidenceSection}</p>
                )}
              </div>
              <button
                type="button"
                data-testid="ea-add-evidence"
                onClick={addEvidence}
                className="shrink-0 text-caption font-semibold text-primary-600 hover:text-primary-700"
              >
                {ADD_EVIDENCE_LABEL}
              </button>
            </div>
            {orderedEvidences.length === 0 ? (
              <p
                data-testid="ea-evidence-empty"
                className="rounded-md border border-dashed border-gray-200 p-2.5 text-body-md text-gray-500"
              >
                {NO_EVIDENCE_TEXT.editable}
              </p>
            ) : (
              orderedEvidences.map((row) => (
                <EvidenceCandidateRow
                  key={row.key}
                  row={row}
                  helpVisible={helpVisible}
                  currentSrcSn={currentSrcSn}
                  hasSelectedObject={selectedLabel !== null}
                  pickedNotice={pickedNotice?.key === row.key ? pickedNotice.text : null}
                  onRemove={removeEvidence}
                  onFieldChange={updateEvidenceField}
                  onAppendCurrentFrame={appendCurrentFrame}
                  onAppendSelectedObject={appendSelectedObject}
                  onRequestPick={onRequestPick}
                />
              ))
            )}

            {update.isError && (
              <p className="mt-2 text-caption text-danger" role="alert">
                {errorMessage(update.error, '저장에 실패했습니다. 다시 시도해 주세요.')}
              </p>
            )}

            {/* REVIEWER(내부 채널) 검토 — 승인/반려. 검토 가능 상태에서만 노출. */}
            {canReview && (
              <div
                data-testid="ea-review-actions"
                className="mt-3 space-y-1 border-t border-gray-200 pt-2"
              >
                <span className="block text-caption font-semibold uppercase text-gray-500">
                  검토
                </span>
                <Textarea
                  data-testid="ea-reject-reason"
                  value={rejectReason}
                  onChange={(e) => setRejectReason(e.target.value)}
                  maxLength={1000}
                  aria-label="반려 사유"
                  placeholder="반려 사유(반려 시 필수)"
                  className="min-h-[72px] resize-y text-body-md"
                />
                <div className="flex items-center gap-2">
                  <Button
                    size="sm"
                    data-testid="ea-approve"
                    onClick={() => review.approve.mutate()}
                    disabled={review.reject.isPending}
                    loading={review.approve.isPending}
                    className="flex-1"
                  >
                    승인
                  </Button>
                  <Button
                    variant="danger"
                    size="sm"
                    data-testid="ea-reject"
                    onClick={handleReject}
                    disabled={rejectReason.trim() === '' || review.approve.isPending}
                    loading={review.reject.isPending}
                    className="flex-1"
                  >
                    반려
                  </Button>
                </div>
              </div>
            )}
          </>
        )}
      </div>
    </AnnotationColumn>
  );
});
