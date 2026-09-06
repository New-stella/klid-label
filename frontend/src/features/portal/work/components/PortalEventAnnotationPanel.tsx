// 포털 라벨링 화면 '메타' 탭의 이벤트 어노테이션 — 영상(rawSn) 단위라 프레임을 옮겨도 같은 값이 선다.
//
// @design SCREEN-029
// @design API-236
// @design API-237
//
// <h3>★ 포털 전용 창구만 부른다</h3>
// 같은 값을 고치는 내부 창구는 저장하면서 원장을 고치고 재검토 표시를 세우고 관제 통지를 발행하고
// 승인 시점 동결본에 영향을 준다. 그것을 부르면 「원본·동결본을 수정하지 않는다 / 관제 통지와
// 산출물 재생성을 일으키지 않는다」가 <b>한 번에</b> 깨진다.
//
// <h3>포털에 두지 않는 것</h3>
// 검토 승인·반려(검수 축)와 자동 생성값 프리필. 프리필의 원천(외부 시계열 위탁 응답)이 이 채널에
// 없다 — 값은 Load 해 온 것과 사람이 쓴 것뿐이다.
//
// 보안(저장형 XSS 방어): 값은 input/textarea value 로만 바인딩 — React 기본 escape.

import { useEffect, useMemo, useRef, useState } from 'react';

import { Button } from '@/components/common/Button';
import { Textarea } from '@/components/common/Textarea';
import { MetaSection } from '@/features/label/components/MetaSection';
import { CaptionCandidateRow } from '@/features/label/components/CaptionCandidateRow';
import { EvidenceCandidateRow } from '@/features/label/components/EvidenceCandidateRow';
import {
  toCaptionRows,
  toEvidenceRows,
} from '@/features/label/components/eventAnnotationForm';
import {
  COT_STEPS,
  INPUT_CLASS,
  MAX_EVENT_CLASS,
  MAX_TEXT,
  type CaptionRow,
  type EvidenceRow,
} from '@/features/label/components/eventAnnotationShared';
import { useUiStore } from '@/stores/useUiStore';

import {
  buildPortalAnnotationPayload,
  nextKey,
  stableStringify,
  type PortalAnnotationForm,
} from '../annotationForm';
import {
  usePortalEventAnnotation,
  usePortalSaveEventAnnotation,
} from '../hooks/usePortalEventAnnotation';
import { portalWorkErrorMessage } from '../workError';

export interface PortalEventAnnotationPanelProps {
  /** 영상 PK. 없으면 조회하지 않는다. */
  rawSn: number | undefined;
}

const FIELD_LABEL_CLASS = 'block text-caption text-gray-500 mb-1';

export function PortalEventAnnotationPanel({ rawSn }: PortalEventAnnotationPanelProps) {
  const pushToast = useUiStore((s) => s.pushToast);
  const { data, isError, error } = usePortalEventAnnotation(rawSn);

  const [eventClass, setEventClass] = useState('');
  const [question, setQuestion] = useState('');
  const [answer, setAnswer] = useState('');
  const [captions, setCaptions] = useState<CaptionRow[]>([]);
  const [evidences, setEvidences] = useState<EvidenceRow[]>([]);

  const save = usePortalSaveEventAnnotation(rawSn, {
    onSuccess: () =>
      pushToast({ variant: 'success', message: '이벤트 어노테이션을 저장했습니다.' }),
    onError: (err) => pushToast({ variant: 'error', message: portalWorkErrorMessage(err) }),
  });

  /**
   * 불러온 본문을 폼에 옮기는 것은 <b>영상당 한 번</b>이다. 이후 재조회(저장 응답 반영 등)로 값이
   * 다시 들어와도 편집 중인 폼을 덮어쓰지 않는다 — 덮으면 사용자가 쓰던 내용이 조용히 사라진다.
   */
  const annotation = data?.annotation ?? null;
  const loadedRawSnRef = useRef<number | undefined>(undefined);
  /** 무변경 판정의 기준 — 불러온 시점의 본문(키 순서 무관 직렬화). */
  const [baseline, setBaseline] = useState<string | null>(null);

  useEffect(() => {
    if (rawSn === undefined || data === undefined) return;
    if (loadedRawSnRef.current === rawSn) return;
    loadedRawSnRef.current = rawSn;
    setEventClass(annotation?.event_class ?? '');
    setQuestion(annotation?.question ?? '');
    setAnswer(annotation?.answer ?? '');
    setCaptions(toCaptionRows(annotation?.caption));
    setEvidences(toEvidenceRows(annotation?.evidence));
    setBaseline(annotation === null ? null : stableStringify(annotation));
  }, [rawSn, data, annotation]);

  const form: PortalAnnotationForm = { eventClass, question, answer, captions, evidences };
  const payload = useMemo(
    () => buildPortalAnnotationPayload(annotation, form),
    // 폼 값이 바뀔 때마다 다시 만든다. `annotation` 은 미지 키 보존을 위한 바탕이다.
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [annotation, eventClass, question, answer, captions, evidences],
  );

  /** ★판정 단위는 구조체 한 벌 전체다(형제 메타 창구는 항목마다 가른다 — 단위가 다르다). */
  const changed = stableStringify(payload) !== baseline;
  const canSave = rawSn !== undefined && changed && eventClass.trim() !== '' && !save.isPending;

  const updateCaption = (key: string, patch: Partial<CaptionRow>) =>
    setCaptions((prev) => prev.map((r) => (r.key === key ? { ...r, ...patch } : r)));
  const updateEvidence = (key: string, patch: Partial<EvidenceRow>) =>
    setEvidences((prev) => prev.map((r) => (r.key === key ? { ...r, ...patch } : r)));

  if (rawSn === undefined) return null;

  if (isError) {
    return (
      <MetaSection title="이벤트 어노테이션">
        {/* ★「없다」와 「남의 것이다」를 가르지 않는다 — 판정은 workError 한 곳이 한다. */}
        <p className="text-caption text-danger" role="alert" data-testid="portal-annotation-error">
          {portalWorkErrorMessage(error)}
        </p>
      </MetaSection>
    );
  }

  return (
    <MetaSection title="이벤트 어노테이션">
      <div data-testid="portal-annotation-panel" className="space-y-2">
        <div>
          <label htmlFor="portal-ea-event-class" className={FIELD_LABEL_CLASS}>
            이벤트 분류
          </label>
          <input
            id="portal-ea-event-class"
            type="text"
            value={eventClass}
            onChange={(e) => setEventClass(e.target.value)}
            maxLength={MAX_EVENT_CLASS}
            className={INPUT_CLASS}
          />
        </div>

        <div>
          <label htmlFor="portal-ea-question" className={FIELD_LABEL_CLASS}>
            질문
          </label>
          <Textarea
            id="portal-ea-question"
            value={question}
            onChange={(e) => setQuestion(e.target.value)}
            maxLength={MAX_TEXT}
            className="min-h-[64px] resize-y text-body-md"
          />
        </div>

        <div>
          <label htmlFor="portal-ea-answer" className={FIELD_LABEL_CLASS}>
            답변
          </label>
          <Textarea
            id="portal-ea-answer"
            value={answer}
            onChange={(e) => setAnswer(e.target.value)}
            maxLength={MAX_TEXT}
            className="min-h-[64px] resize-y text-body-md"
          />
        </div>

        <div>
          <div className="flex items-center justify-between">
            <span className={FIELD_LABEL_CLASS}>캡션 후보</span>
            <button
              type="button"
              data-testid="portal-ea-add-caption"
              onClick={() =>
                setCaptions((prev) => [
                  ...prev,
                  { key: nextKey(prev), captionText: '', cot: Array(COT_STEPS).fill('') },
                ])
              }
              className="text-[11px] text-primary-600 hover:text-primary-700"
            >
              + 추가
            </button>
          </div>
          {captions.map((row) => (
            <CaptionCandidateRow
              key={row.key}
              row={row}
              onRemove={(key) => setCaptions((prev) => prev.filter((r) => r.key !== key))}
              onCaptionTextChange={(key, value) => updateCaption(key, { captionText: value })}
              onCotChange={(key, idx, value) =>
                setCaptions((prev) =>
                  prev.map((r) =>
                    r.key === key
                      ? { ...r, cot: r.cot.map((s, i) => (i === idx ? value : s)) }
                      : r,
                  ),
                )
              }
            />
          ))}
        </div>

        <div>
          <div className="flex items-center justify-between">
            <span className={FIELD_LABEL_CLASS}>근거 후보</span>
            <button
              type="button"
              data-testid="portal-ea-add-evidence"
              onClick={() =>
                setEvidences((prev) => [
                  ...prev,
                  {
                    key: nextKey(prev),
                    evidenceText: '',
                    frameId: '',
                    objId: '',
                    objBbox: '',
                    objLabel: '',
                  },
                ])
              }
              className="text-[11px] text-primary-600 hover:text-primary-700"
            >
              + 추가
            </button>
          </div>
          {evidences.map((row) => (
            /*
             * 캔버스 선택과 이어지는 보조 버튼(현재 프레임 추가·선택 객체 추가)은 넘기지 않는다 —
             * 포털에는 그 배선이 없어 눌러도 영원히 반응이 없는 컨트롤이 된다. 핸들러가 없으면
             * 그 행이 버튼을 렌더하지 않는다.
             */
            <EvidenceCandidateRow
              key={row.key}
              row={row}
              onRemove={(key) => setEvidences((prev) => prev.filter((r) => r.key !== key))}
              onFieldChange={(key, field, value) => updateEvidence(key, { [field]: value })}
            />
          ))}
        </div>

        {/*
          ⚠ 무변경 저장 안내 — 문구는 <b>확정된 것이 없다</b>(사양에도 요건만 있다). 사실만 평이하게
            적는다. 「저장됨」이라고만 알리면 사용자는 자기 확정이 남았다고 믿는다.
        */}
        <p className="text-[11px] leading-snug text-gray-500">
          원본과 같은 내용으로 저장하면 내 작업물로 남지 않습니다. 저장한 값은 이 화면에서만 쓰이며
          원본과 데이터마트에는 반영되지 않습니다.
        </p>

        <Button
          size="sm"
          fullWidth
          onClick={() => canSave && save.mutate(payload)}
          disabled={!canSave}
          loading={save.isPending}
        >
          이벤트 어노테이션 저장
        </Button>
      </div>
    </MetaSection>
  );
}
