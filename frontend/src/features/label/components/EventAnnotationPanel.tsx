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

import { Role } from '@/lib/api/types';
import { useAuthStore } from '@/stores/useAuthStore';
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
  COT_STEPS,
  INPUT_CLASS,
  MAX_EVENT_CLASS,
  MAX_TEXT,
  type CaptionRow,
  type EvidenceRow,
} from './eventAnnotationShared';
import {
  MetaSection,
  META_SAVE_BUTTON_CLASS,
  META_TEXTAREA_CLASS,
} from './MetaSection';

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

/**
 * 라벨링 우측 메타 탭 event_annotation 수동입력·검토 패널.
 * 외부 자동 생성 기본값을 최초 1회 프리필하고 수동 편집 후 저장한다.
 */
export function EventAnnotationPanel({ rawSn, currentSrcSn }: EventAnnotationPanelProps) {
  const pushToast = useUiStore((s) => s.pushToast);
  const role = useAuthStore((s) => s.claims?.role);
  const channel = useAuthStore((s) => s.claims?.channel);
  const isReviewer = role === Role.REVIEWER && channel === 'INTERNAL';

  const { data } = useEventAnnotation(rawSn);
  const update = useUpdateEventAnnotation(rawSn, {
    onSuccess: () =>
      pushToast({ variant: 'success', message: '이벤트 어노테이션이 저장되었습니다.' }),
    onError: (err) =>
      pushToast({ variant: 'error', message: errorMessage(err, '저장에 실패했습니다.') }),
  });
  const review = useEventAnnotationReview(rawSn, {
    onApproveSuccess: () =>
      pushToast({ variant: 'success', message: '검토를 승인했습니다.' }),
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
  const canReview =
    isReviewer && reviewStatus !== null && REVIEWABLE_STATUSES.has(reviewStatus);
  const canSave = rawSn !== undefined && eventClass.trim() !== '' && !update.isPending;

  const buildPayload = useMemo(
    () =>
      (): EventAnnotationPayload => {
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
  const removeCaption = (key: string) =>
    setCaptions((prev) => prev.filter((r) => r.key !== key));
  const updateCaptionText = (key: string, value: string) =>
    setCaptions((prev) =>
      prev.map((r) => (r.key === key ? { ...r, captionText: value } : r)),
    );
  const updateCot = (key: string, idx: number, value: string) =>
    setCaptions((prev) =>
      prev.map((r) =>
        r.key === key
          ? { ...r, cot: r.cot.map((c, i) => (i === idx ? value : c)) }
          : r,
      ),
    );

  const addEvidence = () =>
    setEvidences((prev) => [
      ...prev,
      { key: nextKey(prev), evidenceText: '', frameId: '', objId: '', objBbox: '', objLabel: '' },
    ]);
  const removeEvidence = (key: string) =>
    setEvidences((prev) => prev.filter((r) => r.key !== key));
  const updateEvidenceField = (
    key: string,
    field: keyof Omit<EvidenceRow, 'key'>,
    value: string,
  ) =>
    setEvidences((prev) =>
      prev.map((r) => (r.key === key ? { ...r, [field]: value } : r)),
    );
  const appendCurrentFrame = (key: string) => {
    if (currentSrcSn === undefined) return;
    setEvidences((prev) =>
      prev.map((r) =>
        r.key === key
          ? {
              ...r,
              frameId: r.frameId.trim() === '' ? String(currentSrcSn) : `${r.frameId}, ${currentSrcSn}`,
            }
          : r,
      ),
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
            <span className="text-gray-400">검토 상태</span>
            <span
              data-testid="ea-review-status"
              className="rounded bg-gray-700 px-1.5 py-0.5 text-gray-200"
            >
              {REVIEW_STATUS_LABEL[reviewStatus] ?? reviewStatus}
            </span>
          </div>
        )}

        {/* event_class (필수) */}
        <label className="block text-[11px] text-gray-400" htmlFor="ea-event-class">
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
        <label className="block text-[11px] text-gray-400 mt-2" htmlFor="ea-question">
          질의
        </label>
        <textarea
          id="ea-question"
          data-testid="ea-question"
          value={question}
          onChange={(e) => setQuestion(e.target.value)}
          maxLength={MAX_TEXT}
          rows={2}
          aria-label="질의"
          className={META_TEXTAREA_CLASS}
        />

        {/* answer */}
        <label className="block text-[11px] text-gray-400 mt-2" htmlFor="ea-answer">
          답변
        </label>
        <textarea
          id="ea-answer"
          data-testid="ea-answer"
          value={answer}
          onChange={(e) => setAnswer(e.target.value)}
          maxLength={MAX_TEXT}
          rows={2}
          aria-label="답변"
          className={META_TEXTAREA_CLASS}
        />

        {/* caption 후보 c1..cn */}
        <div className="mt-3 flex items-center justify-between">
          <span className="text-[11px] font-semibold text-gray-400 uppercase">캡션 후보</span>
          <button
            type="button"
            data-testid="ea-add-caption"
            onClick={addCaption}
            className="text-xs text-primary-400 hover:text-primary-300"
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
          <span className="text-[11px] font-semibold text-gray-400 uppercase">근거 후보</span>
          <button
            type="button"
            data-testid="ea-add-evidence"
            onClick={addEvidence}
            className="text-xs text-primary-400 hover:text-primary-300"
          >
            + 후보 추가
          </button>
        </div>
        {evidences.map((row) => (
          <EvidenceCandidateRow
            key={row.key}
            row={row}
            currentSrcSn={currentSrcSn}
            onRemove={removeEvidence}
            onFieldChange={updateEvidenceField}
            onAppendCurrentFrame={appendCurrentFrame}
          />
        ))}

        {update.isError && (
          <p className="text-xs text-red-400 mt-2" role="alert">
            {errorMessage(update.error, '저장에 실패했습니다. 다시 시도해 주세요.')}
          </p>
        )}

        <button
          type="button"
          data-testid="ea-save"
          onClick={handleSave}
          disabled={!canSave}
          className={`${META_SAVE_BUTTON_CLASS} mt-3`}
        >
          {update.isPending ? '저장 중...' : '저장'}
        </button>

        {/* REVIEWER(내부 채널) 검토 — 승인/반려. 검토 가능 상태에서만 노출. */}
        {canReview && (
          <div
            data-testid="ea-review-actions"
            className="mt-3 space-y-1 border-t border-gray-700 pt-2"
          >
            <span className="block text-[11px] font-semibold text-gray-400 uppercase">
              검토
            </span>
            <textarea
              data-testid="ea-reject-reason"
              value={rejectReason}
              onChange={(e) => setRejectReason(e.target.value)}
              maxLength={1000}
              rows={2}
              aria-label="반려 사유"
              placeholder="반려 사유(반려 시 필수)"
              className={META_TEXTAREA_CLASS}
            />
            <div className="flex items-center gap-2">
              <button
                type="button"
                data-testid="ea-approve"
                onClick={() => review.approve.mutate()}
                disabled={review.approve.isPending || review.reject.isPending}
                className="flex-1 rounded bg-primary-600 px-2 py-1.5 text-sm text-white hover:bg-primary-500 disabled:opacity-50"
              >
                {review.approve.isPending ? '승인 중...' : '승인'}
              </button>
              <button
                type="button"
                data-testid="ea-reject"
                onClick={handleReject}
                disabled={
                  rejectReason.trim() === '' ||
                  review.reject.isPending ||
                  review.approve.isPending
                }
                className="flex-1 rounded border border-red-500 px-2 py-1.5 text-sm text-red-300 hover:bg-red-900/30 disabled:opacity-50"
              >
                {review.reject.isPending ? '반려 중...' : '반려'}
              </button>
            </div>
          </div>
        )}
      </MetaSection>
    </div>
  );
}
