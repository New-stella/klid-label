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
import { DscdYn, type Label } from '../types';

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
  /**
   * R4·R5 — 현재 프레임의 <b>화면 기준</b> 폐기여부(서버값 + 미저장 전환을 합친 값).
   * `null` 이면 이 화면이 폐기 축을 다루지 않는다는 뜻이라 버튼도 두지 않는다.
   */
  dscdYn?: DscdYn | null;
  /** 폐기 전환이 아직 저장되지 않았는지 — 미저장 안내를 띄운다. */
  discardPending?: boolean;
  /**
   * 폐기·복원 전환 요청. <b>서버를 부르지 않는다</b> — 화면 표시만 바꾸고 확정은 저장이 한다(D8).
   * 미전달이면 버튼을 렌더하지 않는다(포털 등 폐기 축이 없는 화면).
   */
  onToggleDiscard?: () => void;
  /**
   * 이 영상에서는 폐기·복원 자체가 불가능할 때의 <b>사유</b>(예: 한번이라도 검수 완료된 영상).
   * 지정하면 버튼을 비활성화하고 툴팁(title)으로 사유를 보여준다. [req: P2b]
   *
   * 판정은 이 컴포넌트가 하지 않는다 — `utils/frameDiscardEligibility` 단일 지점에 있다.
   * BE 가 400 으로 거부하는데 그 사실을 누른 뒤에야 알리면 사용자는 저장까지 갔다가 실패한다.
   *
   * ⚠ 문구가 BE 거부 메시지와 <b>다른 것은 의도</b>다: BE 는 "검수가 완료된 영상은…"으로 <b>이력 축을
   * 노출하지 않는다</b>(응답이 내부 판정 축을 알려주는 오라클이 되지 않게 — CWE-209). 화면은 사용자가
   * 이미 아는 자기 영상의 상태를 설명하는 자리라 "한번이라도…"로 정확히 안내한다. 통일하지 말 것.
   */
  discardUnsupportedReason?: string;
  /**
   * R6/D4 — 「시작 버전 선택」 재진입. 그 모달이 <b>버전 목록 · 버전 간 diff · 작업본 diff ·
   * 롤백</b> 네 기능의 유일한 진입점이며 자동 노출은 화면 진입당 1회뿐이라, 이 버튼이 없으면
   * 모달을 닫는 순간 넷 다 그 세션 내내 도달 불가가 된다.
   * 미전달이면 렌더하지 않는다(포털은 버전관리 미제공 — ADR-013).
   */
  onOpenStartVersion?: () => void;
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
  dscdYn = null,
  discardPending = false,
  onToggleDiscard,
  discardUnsupportedReason,
  onOpenStartVersion,
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

      {/* 시작 버전 선택 재진입 — 라벨을 바꾸지 않는 조회 진입점이라 편집 차단(busy)에도 열어 둔다
          (막으면 무엇이 진행 중인지 확인할 길까지 닫힌다). */}
      {onOpenStartVersion && (
        <button
          type="button"
          onClick={onOpenStartVersion}
          data-testid="start-version-open"
          title="검수 승인으로 만들어진 산출 버전을 고르고, 이 프레임의 버전 이력을 확인합니다."
          className="h-9 rounded border border-gray-300 px-3 text-label font-semibold text-gray-700 transition-colors hover:bg-gray-100"
        >
          버전
        </button>
      )}

      {/* 프레임 폐기·복원 — 학습데이터 산출물에서 빼거나 도로 넣는다(R4·R5).
          ⚠ 아이콘을 두지 않는다: 버튼 라벨("프레임 폐기"/"프레임 복원")이 동작을 완전히 서술하고,
            남는 글리프(삭제·숨김)를 재사용하면 "지운다"로 오인된다 — 폐기는 라벨·이미지를 그대로
            보존하는 논리 폐기다.
          ⚠ 잠금(locked)일 때는 저장이 막혀 확정이 불가능하므로 전환도 막는다(무반응 클릭 방지). */}
      {onToggleDiscard && (
        <div className="flex items-center gap-1.5">
          {discardPending && (
            <span
              data-testid="frame-discard-pending"
              className="text-caption text-warning"
              role="status"
            >
              저장해야 확정됩니다
            </span>
          )}
          <button
            type="button"
            onClick={() => {
              // 판정 기준을 disabled 와 <b>같게</b> 둔다(F-6) — 한쪽이 truthy, 다른 쪽이 undefined
              //   비교면 빈 문자열 사유에서 갈린다(현재는 도달 불가하지만 갈리는 것 자체가 결함이다).
              if (editBlocked || locked || discardUnsupportedReason !== undefined) return;
              onToggleDiscard();
            }}
            disabled={editBlocked || locked || discardUnsupportedReason !== undefined}
            aria-pressed={dscdYn === DscdYn.Y}
            data-testid="frame-discard-toggle"
            title={
              discardUnsupportedReason
                ? discardUnsupportedReason
                : dscdYn === DscdYn.Y
                  ? '이 프레임을 학습데이터에 도로 넣습니다. 저장해야 확정됩니다.'
                  : '이 프레임을 학습데이터에서 뺍니다. 라벨과 이미지는 지우지 않으며 저장해야 확정됩니다.'
            }
            className={cn(
              'h-9 rounded border px-3 text-label font-semibold transition-colors disabled:cursor-not-allowed disabled:text-gray-300',
              dscdYn === DscdYn.Y
                ? 'border-warning bg-warning/10 text-warning-700 hover:bg-warning/20'
                : 'border-gray-300 text-gray-700 hover:bg-gray-100',
            )}
          >
            {dscdYn === DscdYn.Y ? '프레임 복원' : '프레임 폐기'}
          </button>
        </div>
      )}

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
