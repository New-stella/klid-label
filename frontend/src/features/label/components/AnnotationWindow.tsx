// 「영상 분석 설명 · 이벤트 어노테이션」 창 — 두 칸을 한 자리에 담은 비모달 창.
// [@design UI-156] [@design UI-107] [@design UI-056] [@design UI-158]
// [@design SCREEN-005] [@design SCREEN-019] [@design API-132] [@design API-066]
//
// 라벨링(SCREEN-005)은 입력, 검수(SCREEN-019)는 읽기 전용이며 <b>같은 창</b>을 쓴다. 두 화면에
// 각각 만들면 문구·배치·저장 규칙이 갈려 한쪽만 갱신된다.
//
// ★저장은 창 아래 버튼 <b>하나</b>다 — 바뀐 칸만 보내고, 한쪽만 실패하면 그 칸 머리에 표시하며
//   성공한 칸의 「저장 안 됨」은 풀린다. 두 칸의 저장 창구가 서로 다르기 때문에(메타 · 이벤트
//   어노테이션) 「모두 성공」과 「일부 성공」을 구분해 알린다.
// ★근거 지정·접기 동안 창은 <b>언마운트되지 않는다</b>(숨을 뿐이다) — 입력하던 값을 잃지 않는다.

import { useCallback, useMemo, useRef, useState, type ReactNode } from 'react';
import { HelpCircle } from 'lucide-react';

import { Button } from '@/components/common/Button';
import { FloatingWindow } from '@/components/common/FloatingWindow';
import { useFloatingWindowLayout } from '@/components/common/floatingWindowContext';
import { Modal } from '@/components/common/Modal';
import { cn } from '@/lib/cn';
import { KRDS_FOCUS } from '@/lib/focusRing';
import { useLabelStore } from '@/stores/useLabelStore';
import { useUiStore } from '@/stores/useUiStore';

import { candidateNumber } from '../annotationSummary';
import type { AnnotationWindowState } from '../hooks/useAnnotationWindow';
import type { Label } from '../types';

import { type AnnotationColumnHandle } from './AnnotationColumn';
import { useAnnotationHelpVisible } from './annotationHelpPreference';
import {
  ANNOTATION_COLUMN_TITLE,
  ANNOTATION_WINDOW_SUBTITLE,
  ANNOTATION_WINDOW_TITLE,
  HELP_TOGGLE_LABEL,
  SAVE_BAR,
  SAVE_RESULT,
  TIMESERIES_COLUMN_TITLE,
  UNSAVED_CLOSE_CONFIRM,
  WINDOW_DIRTY_LABEL,
  pickedNoticeText,
} from './annotationWording';
import { AnnotationWindowStrip } from './AnnotationWindowStrip';
import {
  EventAnnotationPanel,
  type EventAnnotationPanelHandle,
  type EventTypeOption,
} from './EventAnnotationPanel';
import { TimeseriesSidePanel } from './TimeseriesSidePanel';

/** 기본 크기 — 뷰포트의 약 75%. 실제 외부 분석 응답(서술 500자대)이 한눈에 들어오는 크기다. */
const DEFAULT_SIZE = { width: 1440, height: 810 };
/** 최소 크기 — 이보다 작으면 두 칸을 나란히 둘 수 없다. */
const MIN_SIZE = { width: 560, height: 640 };
/** 이 폭 미만이면 두 칸을 위아래로 쌓는다. */
const STACK_BELOW_WIDTH = 880;
const POSITION_STORAGE_KEY = 'klid.annotationWindow.rect';

export interface AnnotationWindowProps {
  mode: 'editable' | 'readOnly';
  /** 영상 — 이벤트 어노테이션 조회 키. */
  rawSn: number | undefined;
  /** 현재 프레임 — 메타 조회 키이자 「지금 프레임 담기」의 값. */
  srcSn: number | undefined;
  state: AnnotationWindowState;
  focusRequestedAt?: number;
  onClose: () => void;
  onFold: () => void;
  onExpand: () => void;
  onPickingChange: (picking: boolean) => void;
  /** 이벤트 분류 이름 조달 — 영상 상세 응답의 전체 검증 이벤트 유형 목록(API-043). */
  eventTypes?: EventTypeOption[];
  /** 근거 지정 띠의 「지금 프레임 {i}/{N}」. */
  frameIndex?: number;
  frameTotal?: number;
  /** 읽기 전용 — 이 영상이 가진 프레임 번호(없는 번호 판정). */
  availableFrameIds?: ReadonlySet<number>;
  /** 읽기 전용 — 근거 프레임으로 이동. 이동했으면 true. */
  onJumpToFrame?: (frameId: number) => boolean;
}

/** 이번 지정에서 담은 것. 「완료」를 눌러야 근거 후보에 들어가고, 저장해야 서버에 남는다. */
interface PickSession {
  key: string;
  frames: number[];
  labels: Label[];
}

/** 접힘 띠가 어떤 사유로 떴는지 — 「잠시 접기」와 근거 프레임 이동은 안내 문구가 다르다. */
type FoldReason = { kind: 'manual' } | { kind: 'evidenceJump'; evidenceNo: number; frameIndex: number };

/** 창 본문 — 폭에 따라 두 칸을 나란히 두거나 위아래로 쌓는다(판정은 창이 내려준다). */
function WindowBody({ left, right }: { left: ReactNode; right: ReactNode }) {
  const { stacked } = useFloatingWindowLayout();
  return (
    <div
      data-testid="annotation-window-columns"
      className={cn(
        'min-h-0 flex-1',
        stacked
          ? 'flex flex-col overflow-y-auto'
          : 'grid grid-cols-[5fr_7fr] overflow-hidden',
      )}
    >
      {left}
      {right}
    </div>
  );
}

export function AnnotationWindow({
  mode,
  rawSn,
  srcSn,
  state,
  focusRequestedAt = 0,
  onClose,
  onFold,
  onExpand,
  onPickingChange,
  eventTypes,
  frameIndex,
  frameTotal,
  availableFrameIds,
  onJumpToFrame,
}: AnnotationWindowProps) {
  const readOnly = mode === 'readOnly';
  const pushToast = useUiStore((s) => s.pushToast);
  const [helpVisible, toggleHelp] = useAnnotationHelpVisible();
  const [maximized, setMaximized] = useState(false);
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [saving, setSaving] = useState(false);
  const [foldReason, setFoldReason] = useState<FoldReason>({ kind: 'manual' });

  const timeseriesRef = useRef<AnnotationColumnHandle>(null);
  const annotationRef = useRef<EventAnnotationPanelHandle>(null);

  const [timeseriesDirty, setTimeseriesDirty] = useState(false);
  const [annotationDirty, setAnnotationDirty] = useState(false);
  // 콜백 신원이 매 렌더 바뀌면 칸의 dirty 보고 효과가 매번 다시 돌아 무한 갱신이 된다.
  const handleTimeseriesDirty = useCallback((d: boolean) => setTimeseriesDirty(d), []);
  const handleAnnotationDirty = useCallback((d: boolean) => setAnnotationDirty(d), []);

  const [pick, setPick] = useState<PickSession | null>(null);
  const [pickedNotice, setPickedNotice] = useState<{ key: string; text: string } | null>(null);

  // 캔버스 선택 객체 — 띠의 「고른 객체 담기」와 상태 표시가 쓴다(패널과 같은 원천을 본다).
  const selectedLabelId = useLabelStore((s) => s.selectedLabelId);
  const labels = useLabelStore((s) => s.labels);
  const selectedLabel = useMemo(
    () => (selectedLabelId ? (labels.find((l) => l.id === selectedLabelId) ?? null) : null),
    [selectedLabelId, labels],
  );

  const dirtyColumns = useMemo(() => {
    const names: string[] = [];
    if (timeseriesDirty) names.push(TIMESERIES_COLUMN_TITLE);
    if (annotationDirty) names.push(ANNOTATION_COLUMN_TITLE);
    return names;
  }, [timeseriesDirty, annotationDirty]);
  const anyDirty = dirtyColumns.length > 0;

  /** 바뀐 칸만 저장한다. 모두 성공하면 true. */
  const runSave = useCallback(async (): Promise<boolean> => {
    setSaving(true);
    try {
      // ⚠ 이 조건은 <b>두 겹 중 바깥쪽</b>이다 — 각 칸의 save() 도 「바뀐 것이 없으면 보내지
      //   않는다」를 스스로 지킨다(그쪽이 전송을 실제로 막는 층이고, 시험도 그 층을 고정한다).
      //   여기서 한 번 더 거르는 이유는 바뀌지 않은 칸을 저장 흐름에 아예 들이지 않기 위해서다.
      const results = await Promise.all([
        timeseriesDirty ? (timeseriesRef.current?.save() ?? Promise.resolve(true)) : true,
        annotationDirty ? (annotationRef.current?.save() ?? Promise.resolve(true)) : true,
      ]);
      const okCount = results.filter(Boolean).length;
      if (okCount === results.length) {
        pushToast({ variant: 'success', message: SAVE_RESULT.success });
        return true;
      }
      pushToast({
        variant: 'error',
        message: okCount === 0 ? SAVE_RESULT.failed : SAVE_RESULT.partial,
      });
      return false;
    } finally {
      setSaving(false);
    }
  }, [timeseriesDirty, annotationDirty, pushToast]);

  const requestClose = useCallback(() => {
    if (anyDirty) {
      setConfirmOpen(true);
      return;
    }
    onClose();
  }, [anyDirty, onClose]);

  const handleSaveAndClose = useCallback(async () => {
    const ok = await runSave();
    setConfirmOpen(false);
    // 실패하면 닫지 않는다 — 닫으면 그 칸의 고친 내용이 사라진다.
    if (ok) onClose();
  }, [runSave, onClose]);

  /* ── 근거 지정 ─────────────────────────────────────────────────────────── */

  const startPick = useCallback(
    (key: string) => {
      setPick({ key, frames: [], labels: [] });
      setPickedNotice(null);
      onPickingChange(true);
    },
    [onPickingChange],
  );

  const capturePickFrame = useCallback(() => {
    if (srcSn === undefined) return;
    setPick((prev) =>
      prev === null || prev.frames.includes(srcSn)
        ? prev
        : { ...prev, frames: [...prev.frames, srcSn] },
    );
  }, [srcSn]);

  const capturePickObject = useCallback(() => {
    const label = selectedLabel;
    if (label === null) return;
    setPick((prev) =>
      prev === null || prev.labels.some((l) => l.id === label.id)
        ? prev
        : { ...prev, labels: [...prev.labels, label] },
    );
  }, [selectedLabel]);

  const completePick = useCallback(() => {
    if (pick === null) return;
    annotationRef.current?.applyPicked(pick.key, { frames: pick.frames, labels: pick.labels });
    setPickedNotice({
      key: pick.key,
      text: pickedNoticeText(pick.frames.length, pick.labels.length),
    });
    setPick(null);
    onPickingChange(false);
  }, [pick, onPickingChange]);

  /** 「취소」 — 이번에 담은 것만 버리고 창은 「완료」와 <b>똑같이</b> 되돌린다. */
  const cancelPick = useCallback(() => {
    setPick(null);
    onPickingChange(false);
  }, [onPickingChange]);

  /* ── 읽기 전용: 근거 프레임 이동 ───────────────────────────────────────── */

  const handleJumpToFrame = useCallback(
    (frameId: number, evidenceKey: string) => {
      const moved = onJumpToFrame?.(frameId) ?? false;
      if (!moved) return;
      setFoldReason({
        kind: 'evidenceJump',
        evidenceNo: candidateNumber(evidenceKey) ?? 0,
        frameIndex: frameId,
      });
      onFold();
    },
    [onJumpToFrame, onFold],
  );

  const handleFold = useCallback(() => {
    setFoldReason({ kind: 'manual' });
    onFold();
  }, [onFold]);

  const hidden = state === 'folded' || state === 'picking';

  const footer = readOnly ? undefined : (
    <div
      className="flex h-14 shrink-0 items-center gap-2.5 border-t border-gray-200 bg-gray-50 px-4"
      data-testid="annotation-window-save-bar"
    >
      <span className="text-caption text-gray-600">
        {anyDirty ? SAVE_BAR.changed(dirtyColumns.join(' · ')) : SAVE_BAR.nothingChanged}
      </span>
      <span className="flex-1" />
      <Button variant="secondary" size="sm" onClick={requestClose} data-testid="annotation-window-close-button">
        {SAVE_BAR.close}
      </Button>
      <Button
        size="sm"
        onClick={() => void runSave()}
        disabled={!anyDirty}
        loading={saving}
        data-testid="annotation-window-save"
      >
        {SAVE_BAR.save}
      </Button>
    </div>
  );

  return (
    <>
      <FloatingWindow
        open
        title={ANNOTATION_WINDOW_TITLE}
        subtitle={readOnly ? ANNOTATION_WINDOW_SUBTITLE.readOnly : ANNOTATION_WINDOW_SUBTITLE.editable}
        dirty={anyDirty}
        dirtyLabel={WINDOW_DIRTY_LABEL}
        folded={hidden}
        onFoldChange={(next) => (next ? handleFold() : onExpand())}
        maximized={maximized}
        onMaximizeChange={setMaximized}
        defaultSize={DEFAULT_SIZE}
        minSize={MIN_SIZE}
        storageKey={POSITION_STORAGE_KEY}
        stackBelowWidth={STACK_BELOW_WIDTH}
        focusRequestedAt={focusRequestedAt}
        onRequestClose={requestClose}
        footer={footer}
        data-testid="annotation-window"
        titleBarExtra={
          /*
           * 도움말 토글 — <b>글자 없이 물음표 아이콘만</b> 둔다(시안 `.iconbtn` 의 `?`).
           *
           * ★그래서 이름을 반드시 준다 — 아이콘만 있는 버튼은 이름이 없으면 보조기기에
           *   「버튼」으로만 읽혀 무엇을 하는 버튼인지 알 수 없다. 지금 도움말이 보이는
           *   상태인지는 `aria-pressed` 가 함께 알린다(이름만으로는 상태가 모호하다).
           */
          <button
            type="button"
            onClick={toggleHelp}
            data-testid="annotation-window-help-toggle"
            aria-label={helpVisible ? HELP_TOGGLE_LABEL.hide : HELP_TOGGLE_LABEL.show}
            aria-pressed={helpVisible}
            title={helpVisible ? HELP_TOGGLE_LABEL.hide : HELP_TOGGLE_LABEL.show}
            // ★제목 표시줄의 형제 버튼(잠시 접기·크게·닫기)과 <b>같은 크기·같은 포커스 링</b>이다.
            //   포커스 링은 공용 상수를 재사용한다 — 그 상수가 「모든 인터랙티브 공통 컴포넌트가
            //   이 상수를 재사용한다」를 규정하고, 제각각 쓰면 한 줄에 선 버튼들의 키보드 포커스
            //   표시가 서로 달라진다.
            className={cn(
              'inline-flex h-8 w-8 shrink-0 items-center justify-center rounded-sm text-gray-600 hover:bg-gray-100',
              KRDS_FOCUS,
            )}
          >
            <HelpCircle aria-hidden="true" className="h-4 w-4" />
          </button>
        }
      >
        <WindowBody
          left={
            <TimeseriesSidePanel
              ref={timeseriesRef}
              srcSn={srcSn}
              readOnly={readOnly}
              onDirtyChange={handleTimeseriesDirty}
            />
          }
          right={
            <EventAnnotationPanel
              ref={annotationRef}
              rawSn={rawSn}
              currentSrcSn={srcSn}
              readOnly={readOnly}
              helpVisible={helpVisible}
              eventTypes={eventTypes}
              onDirtyChange={handleAnnotationDirty}
              onRequestPick={readOnly ? undefined : startPick}
              pickedNotice={pickedNotice}
              onJumpToFrame={readOnly ? handleJumpToFrame : undefined}
              availableFrameIds={availableFrameIds}
            />
          }
        />
      </FloatingWindow>

      {state === 'picking' && pick !== null && (
        <AnnotationWindowStrip
          variant="pick"
          evidenceNo={candidateNumber(pick.key)}
          frameIndex={frameIndex}
          frameTotal={frameTotal}
          selectedObject={
            selectedLabel === null
              ? null
              : {
                  label: selectedLabel.className,
                  id:
                    selectedLabel.trackId ??
                    (selectedLabel.serverId != null
                      ? String(selectedLabel.serverId)
                      : selectedLabel.id),
                }
          }
          pickedCounts={{ frames: pick.frames.length, objects: pick.labels.length }}
          onCaptureFrame={capturePickFrame}
          onCaptureObject={capturePickObject}
          onCancel={cancelPick}
          onComplete={completePick}
        />
      )}

      {state === 'folded' && (
        <AnnotationWindowStrip
          variant={foldReason.kind === 'evidenceJump' ? 'evidenceJump' : 'folded'}
          evidenceNo={foldReason.kind === 'evidenceJump' ? foldReason.evidenceNo : undefined}
          frameIndex={foldReason.kind === 'evidenceJump' ? foldReason.frameIndex : undefined}
          dirty={anyDirty}
          onExpand={onExpand}
        />
      )}

      {/* 미저장 닫기 확인 — 세 갈래다. 「저장하고 닫기」는 저장에 <b>성공해야</b> 닫는다. */}
      <Modal
        open={confirmOpen}
        onClose={() => setConfirmOpen(false)}
        title={UNSAVED_CLOSE_CONFIRM.title}
        description={UNSAVED_CLOSE_CONFIRM.description(dirtyColumns.join(' · '))}
        size="md"
        footer={
          <>
            <Button variant="secondary" onClick={() => setConfirmOpen(false)}>
              {UNSAVED_CLOSE_CONFIRM.keepWriting}
            </Button>
            <Button
              variant="secondary"
              data-testid="annotation-window-discard-close"
              onClick={() => {
                setConfirmOpen(false);
                onClose();
              }}
            >
              {UNSAVED_CLOSE_CONFIRM.discardAndClose}
            </Button>
            <Button
              data-testid="annotation-window-save-close"
              loading={saving}
              onClick={() => void handleSaveAndClose()}
            >
              {UNSAVED_CLOSE_CONFIRM.saveAndClose}
            </Button>
          </>
        }
      />
    </>
  );
}
