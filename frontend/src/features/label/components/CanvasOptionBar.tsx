// 캔버스 상단 옵션바 (SCREEN-005 §캔버스 상단 옵션바 / SCREEN-029 동일 배치).
//
// 사양: "화면을 벗어나지 않고 저장·편집 취소·프레임 이동·화면 배율을 다루는 상시 컨트롤 모음이다.
//        삭제·실행취소·다시실행·저장은 좌측 도구바가 아니라 이 영역에 둔다."
//
// 구성: 프레임 이동 컨트롤(UI-052) · 삭제 · 실행취소/다시실행(UI-054) · 확대/축소 ·
//       라벨 표시숨김 · 저장(UI-053).
//  · 저장은 이 화면의 **유일한 저장 진입점**이며 Ctrl+S 와 동일 동작이다. 화면이 저장 절차
//    (낙관적 동시성 토큰·409 충돌 안내·포털/내부 라우팅)를 소유하므로 `onRequestSave` 로 위임받는다.
//  · 삭제는 선택 객체를 지운다(Del 또는 R 단축키와 동일 동작).
//  · 실행취소/다시실행은 스토어 스택 길이로 가능 여부를 판정해 UndoRedoToolbar 에 prop 으로 넘긴다.
//
// ⚠ 미구현(이번 범위 밖): 사양 §캔버스 상단 옵션바의 `화면 맞춤`. 현재 화면 맞춤은 좌측 도구바
//   (보기 조작)에 있으며, 여기에 같이 두면 같은 동작의 진입점이 둘로 갈린다(별도 정리 대상).

import { Eye, EyeOff, Trash2, ZoomIn, ZoomOut } from 'lucide-react';

import { MAX_ZOOM, MIN_ZOOM, useIsEditBlocked, useLabelStore } from '@/stores/useLabelStore';
import { cn } from '@/lib/cn';

import { formatBindingKeys } from '../hooks/labelingKeymap';
import type { Label } from '../types';

import { FrameNavigator } from './FrameNavigator';
import { SaveCommitButton } from './SaveCommitButton';
import { UndoRedoToolbar } from './UndoRedoToolbar';

export interface CanvasOptionBarProps {
  /** 현재 프레임 순번(0부터). */
  frameIndex: number;
  /** 형제 프레임 총 개수. */
  frameCount: number;
  /** 프레임 이동 요청(0부터) — 미저장 변경 확인은 이 콜백 안에서 한 번만 처리한다. */
  onRequestGoTo: (index: number) => void;
  /** 저장 대상 프레임 식별자. */
  srcSn: number | undefined;
  /** 저장할 라벨 전체(전량 교체 저장). */
  labels: Label[];
  /** 포털 채널 여부 — 저장 경로 라우팅은 화면(onRequestSave)이 담당한다. */
  portalMode?: boolean;
  /** 재비식별 대기 잠금 — 저장·삭제를 비활성화한다(편집 차단과 다른 축). */
  locked?: boolean;
  /** 저장 절차 위임(낙관적 동시성 토큰·409 충돌 안내 포함). */
  onRequestSave: () => void | Promise<void>;
  /** 저장 요청 진행 중. */
  saving?: boolean;
}

const actionButtonClass =
  'flex h-9 w-9 items-center justify-center rounded text-gray-600 transition-colors hover:bg-gray-100 hover:text-gray-900 disabled:cursor-not-allowed disabled:text-gray-300';

/**
 * 버튼 1회 클릭당 배율 변화량.
 *
 * ⚠ `hooks/useLabelingShortcuts.ts(ZOOM_STEP)` 과 **같은 값이어야 한다** — 한쪽만 바꾸면 버튼과
 *   단축키(+/-)의 배율이 갈린다. 단일 상수로 승격하려면 그 파일이 값을 export 해야 한다.
 */
const ZOOM_STEP = 1.2;

export function CanvasOptionBar({
  frameIndex,
  frameCount,
  onRequestGoTo,
  srcSn,
  labels,
  portalMode = false,
  locked = false,
  onRequestSave,
  saving = false,
}: CanvasOptionBarProps) {
  // 편집 차단 단일 판정원 — 장시간 작업 중에는 이동·삭제·되돌리기·저장을 모두 막는다.
  const editBlocked = useIsEditBlocked(srcSn);
  const canUndo = useLabelStore((s) => s.undoStack.length > 0);
  const canRedo = useLabelStore((s) => s.redoStack.length > 0);
  const undo = useLabelStore((s) => s.undo);
  const redo = useLabelStore((s) => s.redo);
  const removeLabel = useLabelStore((s) => s.removeLabel);
  const zoom = useLabelStore((s) => s.zoom);
  const setZoom = useLabelStore((s) => s.setZoom);
  const toggleLabelVisibility = useLabelStore((s) => s.toggleLabelVisibility);
  // 선택 객체가 현재 숨김인지 — 토글 버튼의 aria-pressed/아이콘 상태에 쓴다.
  const selectedHidden = useLabelStore(
    (s) => s.selectedLabelId !== null && s.hiddenLabelIds.has(s.selectedLabelId),
  );

  // ⚠ 선택이 없을 때도 **비활성화하지 않는다**(구 좌측 도구바 삭제 버튼과 동일 동작 보존).
  //   비활성 축을 늘리면 편집 차단 해제 검증(editBlocking)의 "모든 조작이 즉시 복구된다"가
  //   선택 여부에 따라 갈려 회귀 가드가 의미를 잃는다. 선택이 없으면 조용히 no-op 한다.
  const handleDelete = () => {
    if (editBlocked) return;
    const id = useLabelStore.getState().selectedLabelId;
    if (id) removeLabel(id);
  };

  // ⚠ 선택이 없으면 조용히 no-op 한다(삭제 버튼과 동일 계약) — 단축키 T 도 같은 동작이라
  //   버튼만 비활성 축을 늘리면 두 진입점이 갈린다.
  const handleToggleVisibility = () => {
    if (editBlocked) return;
    const id = useLabelStore.getState().selectedLabelId;
    if (id) toggleLabelVisibility(id);
  };

  // 배율은 store 가 MIN/MAX 로 clamp 한다 — 여기서는 경계에서 버튼만 비활성화한다(피드백 없는
  // 무반응 클릭 방지). 판정 상한/하한을 여기서 다시 정의하지 않고 store 상수를 그대로 쓴다.
  const handleZoomIn = () => {
    if (editBlocked) return;
    setZoom(useLabelStore.getState().zoom * ZOOM_STEP);
  };
  const handleZoomOut = () => {
    if (editBlocked) return;
    setZoom(useLabelStore.getState().zoom / ZOOM_STEP);
  };

  const deleteKeys = formatBindingKeys('label.delete');
  const zoomInKeys = formatBindingKeys('zoom.in');
  const zoomOutKeys = formatBindingKeys('zoom.out');
  const visibilityKeys = formatBindingKeys('label.toggleVisibility');

  return (
    <div
      role="toolbar"
      aria-label="캔버스 옵션바"
      data-testid="canvas-option-bar"
      className="flex h-11 shrink-0 items-center gap-2 border-b border-gray-200 bg-white px-3"
    >
      {/* 좌: 편집 액션(삭제 / 실행취소 · 다시실행) */}
      <button
        type="button"
        onClick={handleDelete}
        disabled={editBlocked}
        aria-label="삭제"
        title={deleteKeys ? `삭제 (${deleteKeys})` : '삭제'}
        data-testid="label-option-delete"
        className={cn(actionButtonClass)}
      >
        <Trash2 size={16} />
      </button>
      {/* ⚠ 잠금(locked)은 삭제·실행취소 축을 막지 않는다 — 이관 전 좌측 도구바 동작과 동일하게
          유지한다(이번 변경은 위치 이동이며 차단 축을 새로 늘리지 않는다). */}
      <UndoRedoToolbar
        canUndo={canUndo && !editBlocked}
        canRedo={canRedo && !editBlocked}
        onUndo={undo}
        onRedo={redo}
      />

      {/* 중앙: 프레임 이동 컨트롤 — 위치 표시·이동은 이 컨트롤이 단독 담당한다.
          하단 프레임 타임라인이 이미 스크럽 슬라이더를 제공하므로 여기서는 끈다. */}
      <div className="flex flex-1 justify-center">
        <FrameNavigator
          frameIndex={frameIndex}
          frameCount={frameCount}
          onRequestGoTo={onRequestGoTo}
          showSlider={false}
          disabled={editBlocked}
        />
      </div>

      {/* 우: 보기 조작(확대·축소·표시숨김) + 저장(유일한 진입점).
          ⚠ 확대/축소·표시숨김은 그동안 단축키(+/-/T)에만 있어 **클릭 진입점이 0건**이었다 —
            마우스만 쓰는 작업자에게는 없는 기능이나 마찬가지였다. */}
      <button
        type="button"
        onClick={handleZoomOut}
        disabled={editBlocked || zoom <= MIN_ZOOM}
        aria-label="축소"
        title={zoomOutKeys ? `축소 (${zoomOutKeys})` : '축소'}
        data-testid="label-option-zoom-out"
        className={cn(actionButtonClass)}
      >
        <ZoomOut size={16} />
      </button>
      <button
        type="button"
        onClick={handleZoomIn}
        disabled={editBlocked || zoom >= MAX_ZOOM}
        aria-label="확대"
        title={zoomInKeys ? `확대 (${zoomInKeys})` : '확대'}
        data-testid="label-option-zoom-in"
        className={cn(actionButtonClass)}
      >
        <ZoomIn size={16} />
      </button>
      <button
        type="button"
        onClick={handleToggleVisibility}
        disabled={editBlocked}
        aria-label="라벨 표시/숨김"
        aria-pressed={selectedHidden}
        title={
          visibilityKeys ? `라벨 표시/숨김 (${visibilityKeys})` : '라벨 표시/숨김'
        }
        data-testid="label-option-visibility"
        className={cn(actionButtonClass)}
      >
        {selectedHidden ? <EyeOff size={16} /> : <Eye size={16} />}
      </button>

      <SaveCommitButton
        srcSn={srcSn}
        labels={labels}
        portalMode={portalMode}
        locked={locked}
        onRequestSave={onRequestSave}
        saving={saving}
      />
    </div>
  );
}
