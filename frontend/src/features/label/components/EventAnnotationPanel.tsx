// Phase 4 — event_annotation(외부 VQA/CoT) 수동입력·검토 패널.
//
// 라벨링 RightPanel '메타' 탭(INTERNAL 채널만)에 삽입되는 접이식 편집 패널.
// - useEventAnnotation(rawSn) 로 외부 자동 생성 기본값을 로드 → 폼에 최초 1회 프리필
// - 작업자/검수자가 전 필드 수동 덮어쓰기 후 저장 → useUpdateEventAnnotation(rawSn)
// - caption/evidence 후보(c1..cn) 추가·삭제(하위 Row 컴포넌트), caption 은 CoT 3단계 입력
// - REVIEWER(내부 채널)면 검토 승인/반려 버튼 + reviewStatus 표시(useEventAnnotationReview)
//
// 보안(저장형 XSS 방어): 모든 값은 input/textarea value 로만 바인딩 — React 기본 escape.
//   dangerouslySetInnerHTML 미사용. maxLength 로 입력 크기 제한(BE @Size 정합).
// 불변성: 상태 갱신은 항상 새 배열/객체 생성(spread) — 직접 mutation 금지.
// a11y: 각 입력에 aria-label. 클릭 요소는 시맨틱 <button>.

import { useEffect, useMemo, useRef, useState } from 'react';

import { Button } from '@/components/common/Button';
import { Textarea } from '@/components/common/Textarea';
import { Role } from '@/lib/api/types';
import { useAuthStore } from '@/stores/useAuthStore';
import { useLabelStore } from '@/stores/useLabelStore';
import { useUiStore } from '@/stores/useUiStore';

import { useEventAnnotation } from '../hooks/useEventAnnotation';
import { useEventAnnotationReview } from '../hooks/useEventAnnotationReview';
import { useUpdateEventAnnotation } from '../hooks/useUpdateEventAnnotation';
import type {
  CaptionCandidate,
  EvidenceCandidate,
  EventAnnotationPayload,
} from '../api/eventAnnotation';

import { CaptionCandidateRow } from './CaptionCandidateRow';
import { EvidenceCandidateRow } from './EvidenceCandidateRow';
import {
  errorMessage,
  nextKey,
  parseBboxes,
  parseIntegers,
  parseStrings,
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
import { MetaSection } from './MetaSection';

export interface EventAnnotationPanelProps {
  rawSn: number | undefined;
  /** 현재 프레임 SRC_SN — evidence.frame_id 수동 입력 보조(현재 프레임 추가 버튼). */
  currentSrcSn?: number;
}

/** 검토 상태 → 중립 한글 라벨(기술 모델명 노출 금지). */
const REVIEW_STATUS_LABEL: Record<string, string> = {
  AUTO_GENERATED: '자동 생성',
  PENDING: '검토 대기',
  APPROVED: '승인됨',
  REJECTED: '반려됨',
};

/** 검토(승인/반려) 가능한 상태 — 그 외(APPROVED/REJECTED/미생성)엔 버튼을 노출하지 않는다. */
const REVIEWABLE_STATUSES = new Set(['AUTO_GENERATED', 'PENDING']);

/** 콤마 append(단일값 필드): 기존이 비면 그대로, 있으면 ', ' 로 이어붙임. */
function appendComma(existing: string, value: string): string {
  return existing.trim() === '' ? value : `${existing}, ${value}`;
}

/** 개행 append(obj_bbox 한 줄에 하나): 기존이 비면 그대로, 있으면 '\n' 으로 이어붙임. */
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
 * 라벨링 우측 메타 탭 event_annotation 수동입력·검토 패널.
 * 외부 자동 생성 기본값을 최초 1회 프리필하고 수동 편집 후 저장한다.
 */
export function EventAnnotationPanel({ rawSn, currentSrcSn }: EventAnnotationPanelProps) {
  const pushToast = useUiStore((s) => s.pushToast);
  const role = useAuthStore((s) => s.claims?.role);
  const channel = useAuthStore((s) => s.claims?.channel);
  const isReviewer = role === Role.REVIEWER && channel === 'INTERNAL';

  // 캔버스 선택 객체(evidence 자동연결용) — 필요한 값만 셀렉터로 구독(스토어 전체 구독 금지).
  const selectedLabelId = useLabelStore((s) => s.selectedLabelId);
  const labels = useLabelStore((s) => s.labels);
  const selectedLabel = useMemo(
    () => (selectedLabelId ? (labels.find((l) => l.id === selectedLabelId) ?? null) : null),
    [selectedLabelId, labels],
  );

  const { data } = useEventAnnotation(rawSn);
  const update = useUpdateEventAnnotation(rawSn, {
    onSuccess: () =>
      pushToast({ variant: 'success', message: '이벤트 어노테이션이 저장되었습니다.' }),
    onError: (err) =>
      pushToast({ variant: 'error', message: errorMessage(err, '저장에 실패했습니다.') }),
  });
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

  // 서버 값(외부 자동 생성 기본값)을 폼에 프리필 — 영상(rawSn)당 최초 1회만.
  // 이후 서버 재조회(invalidate)로 payload 가 다시 들어와도 편집 중인 폼을 덮어쓰지 않는다.
  // rawSn 이 바뀌면(다른 영상) 새로 프리필한다.
  const payload = data?.payload;
  const prefilledRawSnRef = useRef<number | undefined>(undefined);
  useEffect(() => {
    if (!payload || rawSn === undefined) return;
    if (prefilledRawSnRef.current === rawSn) return;
    prefilledRawSnRef.current = rawSn;
    setEventClass(payload.event_class ?? '');
    setQuestion(payload.question ?? '');
    setAnswer(payload.answer ?? '');
    setCaptions(toCaptionRows(payload.caption));
    setEvidences(toEvidenceRows(payload.evidence));
  }, [payload, rawSn]);

  const reviewStatus = data?.reviewStatus ?? null;
  const canReview = isReviewer && reviewStatus !== null && REVIEWABLE_STATUSES.has(reviewStatus);
  const canSave = rawSn !== undefined && eventClass.trim() !== '' && !update.isPending;

  const buildPayload = useMemo(
    () => (): EventAnnotationPayload => {
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
    },
    [eventClass, question, answer, captions, evidences],
  );

  const handleSave = () => {
    if (!canSave) return;
    update.mutate(buildPayload());
  };

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

  // 캔버스 선택 라벨 1건의 값을 evidence 행 obj_* 에 append.
  //  - obj_id: trackId(프레임 간 동일 객체) 우선 → serverId → client id
  //  - obj_label: className / obj_bbox: shape 도출(정수 반올림, MASK/유효점없음=스킵)
  //  - frame_id: currentSrcSn(정의 시) 콤마 append
  // 중복 방지: obj_id 가 이미 있으면 obj_id/obj_label/obj_bbox 3개를 세트로 skip(병렬 배열
  //   정렬 유지). frame_id 는 독립 dedup(다른 객체를 같은 프레임에서 추가해도 1개만 유지).
  const appendSelectedObject = (key: string) => {
    const label = selectedLabel;
    if (!label) return;
    const objIdVal = label.trackId ?? (label.serverId != null ? String(label.serverId) : label.id);
    const objLabelVal = label.className;
    const bbox = bboxFromShape(label.shape);
    const bboxLine = bbox ? bbox.map((v) => Math.round(v)).join(',') : null;
    setEvidences((prev) =>
      prev.map((r) => {
        if (r.key !== key) return r;
        const next: EvidenceRow = { ...r };
        // obj_id 중복이면 obj_* 세트 전체 skip(label/bbox 병렬 배열 정렬 보존).
        if (!commaHas(r.objId, objIdVal)) {
          next.objId = appendComma(r.objId, objIdVal);
          next.objLabel = appendComma(r.objLabel, objLabelVal);
          if (bboxLine !== null) next.objBbox = appendNewline(r.objBbox, bboxLine);
        }
        if (currentSrcSn !== undefined) {
          next.frameId = appendCommaUnique(r.frameId, String(currentSrcSn));
        }
        return next;
      }),
    );
  };

  const handleReject = () => {
    if (rejectReason.trim() === '' || review.reject.isPending) return;
    review.reject.mutate(rejectReason.trim());
  };

  return (
    <div data-testid="event-annotation-panel">
      <MetaSection title="이벤트 어노테이션">
        {/* 검토 상태 표시 — 중립 한글 라벨. */}
        {reviewStatus !== null && (
          <div className="mb-2 flex items-center gap-1 text-[11px]">
            <span className="text-gray-500">검토 상태</span>
            <span
              data-testid="ea-review-status"
              className="rounded bg-gray-100 px-1.5 py-0.5 text-gray-700"
            >
              {REVIEW_STATUS_LABEL[reviewStatus] ?? reviewStatus}
            </span>
          </div>
        )}

        {/* event_class (필수) */}
        <label className="block text-[11px] text-gray-500" htmlFor="ea-event-class">
          이벤트 분류 (필수)
        </label>
        <input
          id="ea-event-class"
          data-testid="ea-event-class"
          type="text"
          value={eventClass}
          onChange={(e) => setEventClass(e.target.value)}
          maxLength={MAX_EVENT_CLASS}
          aria-label="이벤트 분류"
          placeholder="예: 화재, 침입, 배회"
          className={INPUT_CLASS}
        />

        {/* question */}
        <label className="block text-[11px] text-gray-500 mt-2" htmlFor="ea-question">
          질의
        </label>
        <Textarea
          id="ea-question"
          data-testid="ea-question"
          value={question}
          onChange={(e) => setQuestion(e.target.value)}
          maxLength={MAX_TEXT}
          aria-label="질의"
          className="min-h-[72px] resize-y text-body-md"
        />

        {/* answer */}
        <label className="block text-[11px] text-gray-500 mt-2" htmlFor="ea-answer">
          답변
        </label>
        <Textarea
          id="ea-answer"
          data-testid="ea-answer"
          value={answer}
          onChange={(e) => setAnswer(e.target.value)}
          maxLength={MAX_TEXT}
          aria-label="답변"
          className="min-h-[72px] resize-y text-body-md"
        />

        {/* caption 후보 c1..cn */}
        <div className="mt-3 flex items-center justify-between">
          <span className="text-[11px] font-semibold text-gray-500 uppercase">캡션 후보</span>
          <button
            type="button"
            data-testid="ea-add-caption"
            onClick={addCaption}
            className="text-caption text-primary-600 hover:text-primary-700"
          >
            + 후보 추가
          </button>
        </div>
        {captions.map((row) => (
          <CaptionCandidateRow
            key={row.key}
            row={row}
            onRemove={removeCaption}
            onCaptionTextChange={updateCaptionText}
            onCotChange={updateCot}
          />
        ))}

        {/* evidence 후보 c1..cn */}
        <div className="mt-3 flex items-center justify-between">
          <span className="text-[11px] font-semibold text-gray-500 uppercase">근거 후보</span>
          <button
            type="button"
            data-testid="ea-add-evidence"
            onClick={addEvidence}
            className="text-caption text-primary-600 hover:text-primary-700"
          >
            + 후보 추가
          </button>
        </div>
        {evidences.map((row) => (
          <EvidenceCandidateRow
            key={row.key}
            row={row}
            currentSrcSn={currentSrcSn}
            hasSelectedObject={selectedLabel !== null}
            onRemove={removeEvidence}
            onFieldChange={updateEvidenceField}
            onAppendCurrentFrame={appendCurrentFrame}
            onAppendSelectedObject={appendSelectedObject}
          />
        ))}

        {update.isError && (
          <p className="text-caption text-danger mt-2" role="alert">
            {errorMessage(update.error, '저장에 실패했습니다. 다시 시도해 주세요.')}
          </p>
        )}

        <Button
          size="sm"
          fullWidth
          data-testid="ea-save"
          onClick={handleSave}
          disabled={!canSave}
          loading={update.isPending}
          className="mt-3"
        >
          저장
        </Button>

        {/* REVIEWER(내부 채널) 검토 — 승인/반려. 검토 가능 상태에서만 노출. */}
        {canReview && (
          <div
            data-testid="ea-review-actions"
            className="mt-3 space-y-1 border-t border-gray-200 pt-2"
          >
            <span className="block text-[11px] font-semibold text-gray-500 uppercase">검토</span>
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
      </MetaSection>
    </div>
  );
}
