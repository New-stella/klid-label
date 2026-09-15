// 포털 라벨링 — 캔버스 위 도구 줄(되돌리기 · 프레임 이동 · 확대 · 표시·숨김 · 저장).
//
// <h3>흐름은 원본 그대로</h3>
// 원본 캔버스 옵션바(`CanvasOptionBar`)와 그 안의 조각(`UndoRedoToolbar` · `FrameNavigator` ·
// `SaveCommitButton`)이 쓰던 판정을 옮겼다 —
//   · 되돌리기/다시 실행은 스토어 스택 길이, 편집 차단 중에는 막는다
//   · 프레임 이동은 전부 화면의 `onRequestGoTo` 한 곳으로 보낸다(미저장 확인이 거기 한 번 있다).
//     번호 칸은 Enter 로만 옮기고, 빈 값 · 숫자 아님 · 범위 밖 · 포커스 잃음은 원래 번호로 되돌린다
//   · 확대/축소는 스토어 배율에 1.2 배씩, 끝에 닿으면 그 버튼만 잠근다
//   · 표시·숨김은 고른 객체가 있어야 눌리고, 없으면 잠긴 채 무엇을 먼저 할지 말한다
//   · 저장은 화면의 저장 절차(`onRequestSave`)에 맡긴다 — 이 화면의 유일한 저장 버튼이다.
//     편집 차단 · 영상 잠금 중에는 잠기고, 저장 안 한 객체 수를 괄호로 붙인다
//   · 포털판에는 옵션바의 「삭제」 버튼이 원래 없다(객체 목록 줄마다 「객체 삭제」가 있다)
//
// <h3>모양 — 포털 저작도구 화면이 정본</h3>
// 포털 화면(KLID_Portal `AuthoringLabelingView` 의 도구 줄)과 같다 — `EditorBar` 세 자리에 아이콘 버튼(lg)과
// 번호 칸, 끝에 저장(채움).

import { useState, type KeyboardEvent } from 'react';
import { Button, TextInput } from 'krds-react';
import {
  ChevronLeft,
  ChevronRight,
  ChevronsLeft,
  ChevronsRight,
  Eye,
  EyeOff,
  Redo2,
  Undo2,
  ZoomIn,
  ZoomOut,
} from 'lucide-react';

import { EditorBar, IconButton } from '@portal/components/custom';
import { formatBindingKeys } from '@/features/label/hooks/labelingKeymap';
import { MAX_ZOOM, MIN_ZOOM, useIsEditBlocked, useLabelStore } from '@/stores/useLabelStore';

/**
 * 버튼 한 번에 바뀌는 배율.
 * ⚠ 원본 옵션바 · 단축키(`useLabelingShortcuts`)와 같은 값이어야 한다 — 갈리면 버튼과 +/- 키의 배율이 다르다.
 */
const ZOOM_STEP = 1.2;

/** 단축키가 있으면 「이름 (키)」, 없으면 이름만. */
const withKeys = (name: string, id: string) => {
  const keys = formatBindingKeys(id);
  return keys ? `${name} (${keys})` : name;
};

export interface PortalLabelingToolbarProps {
  frameIndex: number;
  frameCount: number;
  onRequestGoTo: (index: number) => void;
  srcSn: number | undefined;
  /** 재비식별 대기 잠금 — 저장을 막는다(편집 차단과 다른 축). */
  locked: boolean;
  saving: boolean;
  onRequestSave: () => void | Promise<void>;
}

export function PortalLabelingToolbar({
  frameIndex,
  frameCount,
  onRequestGoTo,
  srcSn,
  locked,
  saving,
  onRequestSave,
}: PortalLabelingToolbarProps) {
  const editBlocked = useIsEditBlocked(srcSn);
  const canUndo = useLabelStore((s) => s.undoStack.length > 0);
  const canRedo = useLabelStore((s) => s.redoStack.length > 0);
  const undo = useLabelStore((s) => s.undo);
  const redo = useLabelStore((s) => s.redo);
  const zoom = useLabelStore((s) => s.zoom);
  const setZoom = useLabelStore((s) => s.setZoom);
  const toggleLabelVisibility = useLabelStore((s) => s.toggleLabelVisibility);
  const hasSelection = useLabelStore((s) => s.selectedLabelId !== null);
  const selectedHidden = useLabelStore(
    (s) => s.selectedLabelId !== null && s.hiddenLabelIds.has(s.selectedLabelId),
  );
  const dirtyCount = useLabelStore((s) => s.dirtyLabels.size);

  // 번호 칸에 적는 중인 글 — null 이면 지금 프레임 번호를 보인다.
  const [numberDraft, setNumberDraft] = useState<string | null>(null);

  const maxIndex = Math.max(0, frameCount - 1);
  const empty = frameCount === 0;
  const atFirst = empty || frameIndex <= 0;
  const atLast = empty || frameIndex >= maxIndex;

  const go = (index: number) => {
    if (editBlocked || empty) return;
    onRequestGoTo(Math.min(maxIndex, Math.max(0, index)));
  };

  const commitNumber = () => {
    const raw = numberDraft;
    setNumberDraft(null);
    if (raw === null) return;
    const trimmed = raw.trim();
    if (trimmed === '') return;
    const parsed = Number(trimmed);
    if (!Number.isInteger(parsed)) return;
    if (parsed < 1 || parsed > frameCount) return;
    if (parsed - 1 === frameIndex) return;
    onRequestGoTo(parsed - 1);
  };

  const handleToggleVisibility = () => {
    if (editBlocked || !hasSelection) return;
    const id = useLabelStore.getState().selectedLabelId;
    if (id) toggleLabelVisibility(id);
  };

  const saveDisabled = srcSn === undefined || editBlocked || locked;

  return (
    <EditorBar
      start={
        <>
          {/* 되돌릴 작업이 없으면 자리를 지키고 잠가 둔다 */}
          <IconButton
            size="lg"
            aria-label="실행 취소"
            title={withKeys('실행 취소', 'edit.undo')}
            data-testid="undo-button"
            disabled={!canUndo || editBlocked}
            onClick={undo}
          >
            <Undo2 aria-hidden />
          </IconButton>
          <IconButton
            size="lg"
            aria-label="다시 실행"
            title={withKeys('다시 실행', 'edit.redo')}
            data-testid="redo-button"
            disabled={!canRedo || editBlocked}
            onClick={redo}
          >
            <Redo2 aria-hidden />
          </IconButton>
        </>
      }
      center={
        <>
          <IconButton
            size="lg"
            aria-label="처음 프레임"
            title="처음 프레임"
            disabled={editBlocked || atFirst}
            onClick={() => go(0)}
          >
            <ChevronsLeft aria-hidden />
          </IconButton>
          <IconButton
            size="lg"
            aria-label="이전 프레임"
            title="이전 프레임"
            disabled={editBlocked || atFirst}
            onClick={() => go(frameIndex - 1)}
          >
            <ChevronLeft aria-hidden />
          </IconButton>
          <TextInput
            className="klid-labeling-frame-input"
            size="small"
            inputMode="numeric"
            aria-label="프레임 번호"
            data-testid="frame-number-input"
            disabled={editBlocked || empty}
            value={numberDraft ?? String(empty ? 0 : frameIndex + 1)}
            onChange={(v) => setNumberDraft(v)}
            onKeyDown={(e: KeyboardEvent<HTMLInputElement>) => {
              if (e.key === 'Enter') {
                e.preventDefault();
                commitNumber();
              } else if (e.key === 'Escape') {
                setNumberDraft(null);
              }
            }}
            // 포커스를 잃으면 옮기지 않고 되돌린다 — 실수로 남긴 값이 조용히 이동을 일으키지 않게(원본 규칙).
            onBlur={() => setNumberDraft(null)}
          />
          <span className="klid-labeling-frame-total" data-testid="frame-total-count">
            / {frameCount}
          </span>
          <IconButton
            size="lg"
            aria-label="다음 프레임"
            title="다음 프레임"
            disabled={editBlocked || atLast}
            onClick={() => go(frameIndex + 1)}
          >
            <ChevronRight aria-hidden />
          </IconButton>
          <IconButton
            size="lg"
            aria-label="마지막 프레임"
            title="마지막 프레임"
            disabled={editBlocked || atLast}
            onClick={() => go(maxIndex)}
          >
            <ChevronsRight aria-hidden />
          </IconButton>
        </>
      }
      end={
        <>
          <IconButton
            size="lg"
            aria-label="축소"
            title={withKeys('축소', 'zoom.out')}
            data-testid="label-option-zoom-out"
            disabled={editBlocked || zoom <= MIN_ZOOM}
            onClick={() => !editBlocked && setZoom(useLabelStore.getState().zoom / ZOOM_STEP)}
          >
            <ZoomOut aria-hidden />
          </IconButton>
          <IconButton
            size="lg"
            aria-label="확대"
            title={withKeys('확대', 'zoom.in')}
            data-testid="label-option-zoom-in"
            disabled={editBlocked || zoom >= MAX_ZOOM}
            onClick={() => !editBlocked && setZoom(useLabelStore.getState().zoom * ZOOM_STEP)}
          >
            <ZoomIn aria-hidden />
          </IconButton>
          {/* 고른 객체 하나를 숨기고 보인다 — 고른 객체가 없으면 잠기고 무엇을 먼저 할지 말한다 */}
          <IconButton
            size="lg"
            aria-label="라벨 표시·숨김"
            title={hasSelection ? withKeys('라벨 표시·숨김', 'label.toggleVisibility') : '객체를 먼저 선택해 주세요'}
            data-testid="label-option-visibility"
            aria-pressed={selectedHidden}
            disabled={editBlocked || !hasSelection}
            onClick={handleToggleVisibility}
          >
            {selectedHidden ? <EyeOff aria-hidden /> : <Eye aria-hidden />}
          </IconButton>
          <Button
            size="small"
            variant="primary"
            className={saving ? 'klid-labeling-save klid-btn-busy' : 'klid-labeling-save'}
            title={withKeys('저장', 'edit.save')}
            data-testid="label-toolbar-save"
            disabled={saveDisabled || saving}
            aria-busy={saving || undefined}
            onClick={() => {
              if (saveDisabled || saving) return;
              void onRequestSave();
            }}
          >
            {dirtyCount > 0 ? `저장 (${dirtyCount})` : '저장'}
          </Button>
        </>
      }
    />
  );
}
