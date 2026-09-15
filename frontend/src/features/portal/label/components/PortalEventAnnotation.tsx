// 포털 라벨링 — 「메타」 탭의 이벤트 어노테이션(영상 단위라 프레임을 옮겨도 같은 값이 선다).
//
// <h3>흐름은 원본 그대로</h3>
// 원본 포털 어노테이션 판(`PortalEventAnnotationPanel`)과 같은 창구 · 같은 규칙이다 —
//   · 포털 전용 창구만 부른다(원본 · 동결본 · 관제 통지를 건드리지 않는다)
//   · 불러온 본문을 폼에 옮기는 것은 **영상당 한 번** — 재조회가 편집 중인 폼을 덮지 않는다
//   · 저장 본문은 불러온 본문 위에 덮어써 화면이 모르는 키를 잃지 않는다(`buildPortalAnnotationPayload`)
//   · 바뀐 것이 있고 이벤트 분류가 비어 있지 않을 때만 저장할 수 있다
//   · 근거 후보의 「현재 프레임 추가」 · 「선택 객체 추가」 는 포털에 배선이 없어 두지 않는다(원본 규칙)
//
// <h3>모양 — 포털 저작도구 화면이 정본</h3>
// 포털 화면(KLID_Portal `AuthoringLabelingView` 의 이벤트 어노테이션)과 같다 — 접고 펴는 줄 안에 칸 셋,
// 후보 둘은 이름 줄 끝의 「추가」 와 후보마다 도구 판 한 장(이름 줄 끝에 삭제).
// ★ 이벤트 분류는 원본대로 **한 줄 입력칸(최대 100자)** 이다. 포털 화면 견본은 재난유형 드롭다운인데,
//   저장되는 값의 꼴이 달라지므로 흐름(원본)을 따랐다.

import { useEffect, useMemo, useRef, useState } from 'react';
import { Button, TextInput, Textarea } from 'krds-react';
import { Plus, Trash2 } from 'lucide-react';

import { Alert, FilterPanel, IconButton } from '@portal/components/custom';
import { ToolPanel, ToolRow } from '@portal/pages/workspace/authoring/ToolPanel';
import {
  toCaptionRows,
  toEvidenceRows,
} from '@/features/label/components/eventAnnotationForm';
import {
  COT_LABELS,
  COT_STEPS,
  MAX_COT_STEP,
  MAX_EVENT_CLASS,
  MAX_ID,
  MAX_TEXT,
  type CaptionRow,
  type EvidenceRow,
} from '@/features/label/components/eventAnnotationShared';
import {
  buildPortalAnnotationPayload,
  nextKey,
  stableStringify,
  type PortalAnnotationForm,
} from '@/features/portal/work/annotationForm';
import {
  usePortalEventAnnotation,
  usePortalSaveEventAnnotation,
} from '@/features/portal/work/hooks/usePortalEventAnnotation';
import { portalWorkErrorMessage } from '@/features/portal/work/workError';
import { useUiStore } from '@/stores/useUiStore';

const EVENT_SAVE_NOTE =
  '원본과 같은 내용으로 저장하면 내 작업물로 남지 않습니다. 저장한 값은 이 화면에서만 쓰이며 원본과 데이터마트에는 반영되지 않습니다.';

export function PortalEventAnnotation({ rawSn }: { rawSn: number | undefined }) {
  const pushToast = useUiStore((s) => s.pushToast);
  const { data, isError, error } = usePortalEventAnnotation(rawSn);

  const [eventClass, setEventClass] = useState('');
  const [question, setQuestion] = useState('');
  const [answer, setAnswer] = useState('');
  const [captions, setCaptions] = useState<CaptionRow[]>([]);
  const [evidences, setEvidences] = useState<EvidenceRow[]>([]);

  const save = usePortalSaveEventAnnotation(rawSn, {
    onSuccess: () => pushToast({ variant: 'success', message: '이벤트 어노테이션을 저장했습니다.' }),
    onError: (err) => pushToast({ variant: 'error', message: portalWorkErrorMessage(err) }),
  });

  const annotation = data?.annotation ?? null;
  const loadedRawSnRef = useRef<number | undefined>(undefined);
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
    // 폼 값이 바뀔 때마다 다시 만든다. `annotation` 은 모르는 키를 지키기 위한 바탕이다(원본과 같다).
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [annotation, eventClass, question, answer, captions, evidences],
  );

  const changed = stableStringify(payload) !== baseline;
  const canSave = rawSn !== undefined && changed && eventClass.trim() !== '' && !save.isPending;

  const updateCaption = (key: string, patch: Partial<CaptionRow>) =>
    setCaptions((prev) => prev.map((r) => (r.key === key ? { ...r, ...patch } : r)));
  const updateEvidence = (key: string, patch: Partial<EvidenceRow>) =>
    setEvidences((prev) => prev.map((r) => (r.key === key ? { ...r, ...patch } : r)));

  if (rawSn === undefined) return null;

  if (isError) {
    return (
      <Alert tone="danger" title="이벤트 어노테이션">
        <span data-testid="portal-annotation-error">{portalWorkErrorMessage(error)}</span>
      </Alert>
    );
  }

  return (
    <>
      <FilterPanel surface="bare" layout="stack" aria-label="이벤트 어노테이션" className="klid-labeling-meta">
        <FilterPanel.Disclosure label="이벤트 어노테이션" defaultOpen>
          <FilterPanel.Field label="이벤트 분류">
            <TextInput
              size="small"
              aria-label="이벤트 분류"
              maxLength={MAX_EVENT_CLASS}
              value={eventClass}
              onChange={setEventClass}
            />
          </FilterPanel.Field>
          <FilterPanel.Field label="질문">
            <Textarea aria-label="질문" maxLength={MAX_TEXT} value={question} onChange={setQuestion} />
          </FilterPanel.Field>
          <FilterPanel.Field label="답변">
            <Textarea aria-label="답변" maxLength={MAX_TEXT} value={answer} onChange={setAnswer} />
          </FilterPanel.Field>

          {/* 후보 둘은 여러 개를 쌓는 목록이라 이름 줄 끝에 더하는 걸음이 선다. 카드는 저장 전까지 화면에서만 */}
          <ToolRow label="캡션 후보">
            <Button
              size="small"
              variant="text"
              data-testid="portal-ea-add-caption"
              onClick={() =>
                setCaptions((prev) => [
                  ...prev,
                  { key: nextKey(prev), captionText: '', cot: Array(COT_STEPS).fill('') },
                ])
              }
            >
              <Plus aria-hidden />
              추가
            </Button>
          </ToolRow>
          {captions.map((row) => (
            <ToolPanel
              key={row.key}
              title={row.key}
              aside={
                <IconButton
                  size="sm"
                  tone="danger"
                  aria-label={`캡션 후보 ${row.key} 삭제`}
                  title="삭제"
                  onClick={() => setCaptions((prev) => prev.filter((r) => r.key !== row.key))}
                >
                  <Trash2 aria-hidden />
                </IconButton>
              }
            >
              <TextInput
                size="small"
                aria-label={`캡션 후보 ${row.key} 캡션`}
                placeholder="caption_text"
                maxLength={MAX_TEXT}
                value={row.captionText}
                onChange={(v) => updateCaption(row.key, { captionText: v })}
              />
              {row.cot.map((step, i) => (
                <TextInput
                  key={i}
                  size="small"
                  aria-label={`캡션 후보 ${row.key} CoT ${COT_LABELS[i]}`}
                  placeholder={`CoT ${COT_LABELS[i]}`}
                  maxLength={MAX_COT_STEP}
                  value={step}
                  onChange={(v) =>
                    updateCaption(row.key, { cot: row.cot.map((s, idx) => (idx === i ? v : s)) })
                  }
                />
              ))}
            </ToolPanel>
          ))}

          <ToolRow label="근거 후보">
            <Button
              size="small"
              variant="text"
              data-testid="portal-ea-add-evidence"
              onClick={() =>
                setEvidences((prev) => [
                  ...prev,
                  { key: nextKey(prev), evidenceText: '', frameId: '', objId: '', objBbox: '', objLabel: '' },
                ])
              }
            >
              <Plus aria-hidden />
              추가
            </Button>
          </ToolRow>
          {evidences.map((row) => (
            <ToolPanel
              key={row.key}
              title={row.key}
              aside={
                <IconButton
                  size="sm"
                  tone="danger"
                  aria-label={`근거 후보 ${row.key} 삭제`}
                  title="삭제"
                  onClick={() => setEvidences((prev) => prev.filter((r) => r.key !== row.key))}
                >
                  <Trash2 aria-hidden />
                </IconButton>
              }
            >
              <TextInput
                size="small"
                aria-label={`근거 후보 ${row.key} 근거`}
                placeholder="evidence_text"
                maxLength={MAX_TEXT}
                value={row.evidenceText}
                onChange={(v) => updateEvidence(row.key, { evidenceText: v })}
              />
              <TextInput
                size="small"
                aria-label={`근거 후보 ${row.key} 프레임`}
                placeholder="frame_id (콤마 구분, 정수)"
                value={row.frameId}
                onChange={(v) => updateEvidence(row.key, { frameId: v })}
              />
              <TextInput
                size="small"
                aria-label={`근거 후보 ${row.key} 객체`}
                placeholder="obj_id (콤마 구분)"
                hint={`원소당 최대 ${MAX_ID}자`}
                value={row.objId}
                onChange={(v) => updateEvidence(row.key, { objId: v })}
              />
              <TextInput
                size="small"
                aria-label={`근거 후보 ${row.key} 라벨`}
                placeholder="obj_label (콤마 구분)"
                hint={`원소당 최대 ${MAX_ID}자`}
                value={row.objLabel}
                onChange={(v) => updateEvidence(row.key, { objLabel: v })}
              />
              <Textarea
                aria-label={`근거 후보 ${row.key} 영역`}
                placeholder="obj_bbox (한 줄에 하나: x1,y1,x2,y2)"
                value={row.objBbox}
                onChange={(v) => updateEvidence(row.key, { objBbox: v })}
              />
            </ToolPanel>
          ))}
        </FilterPanel.Disclosure>
      </FilterPanel>

      <div className="klid-labeling-meta-save">
        <p className="klid-tool-panel-note">{EVENT_SAVE_NOTE}</p>
        <Button
          size="small"
          variant="secondary"
          disabled={!canSave}
          className={save.isPending ? 'klid-btn-busy' : undefined}
          aria-busy={save.isPending || undefined}
          onClick={() => canSave && save.mutate(payload)}
        >
          이벤트 어노테이션 저장
        </Button>
      </div>
    </>
  );
}
