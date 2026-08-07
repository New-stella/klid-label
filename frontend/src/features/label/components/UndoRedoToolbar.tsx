// UI-054 UndoRedoToolbar — 실행취소/다시실행 시각 버튼.
//
// 사양: canUndo/canRedo 로 각각 비활성 여부를 판정하고, 클릭 시 onUndo/onRedo 를 호출한다.
// 단축키(Ctrl+Z / Ctrl+Shift+Z)는 화면 레벨(useLabelingShortcuts)에서 전역 처리하며 이 버튼과
// 동일한 동작을 수행한다. 위치는 캔버스 상단 옵션바(SCREEN-005 §캔버스 상단 옵션바) —
// 좌측 도구바가 아니다.
//
// ★스토어를 직접 구독하지 않고 4개 prop 을 받는다 — 상위가 편집 차단(busy)·잠금 같은 다른 축과
//   합성해 판정해야 하고, 다른 편집 스택 위에서도 같은 컨트롤을 재사용할 수 있어야 한다.

import { Redo2, Undo2 } from 'lucide-react';

import { cn } from '@/lib/cn';

import { formatBindingKeys } from '../hooks/labelingKeymap';

export interface UndoRedoToolbarProps {
  /** 실행취소 가능 여부. 거짓이면 버튼을 비활성화한다. */
  canUndo: boolean;
  /** 다시실행 가능 여부. 거짓이면 버튼을 비활성화한다. */
  canRedo: boolean;
  /** 실행취소를 수행한다. */
  onUndo: () => void;
  /** 다시실행을 수행한다. */
  onRedo: () => void;
}

const buttonClass =
  'flex h-9 w-9 items-center justify-center rounded text-gray-600 transition-colors hover:bg-gray-100 hover:text-gray-900 disabled:cursor-not-allowed disabled:text-gray-300';

export function UndoRedoToolbar({ canUndo, canRedo, onUndo, onRedo }: UndoRedoToolbarProps) {
  // 단축키 표기는 SHORTCUT_KEYMAP 단일 출처에서 파생한다(툴팁 오표기 근절).
  const undoKeys = formatBindingKeys('edit.undo');
  const redoKeys = formatBindingKeys('edit.redo');

  return (
    <div className="flex items-center gap-1" role="toolbar" aria-label="실행 취소/다시 실행">
      <button
        type="button"
        onClick={onUndo}
        disabled={!canUndo}
        aria-label="실행 취소"
        title={undoKeys ? `실행 취소 (${undoKeys})` : '실행 취소'}
        data-testid="undo-button"
        className={cn(buttonClass)}
      >
        <Undo2 size={16} />
      </button>
      <button
        type="button"
        onClick={onRedo}
        disabled={!canRedo}
        aria-label="다시 실행"
        title={redoKeys ? `다시 실행 (${redoKeys})` : '다시 실행'}
        data-testid="redo-button"
        className={cn(buttonClass)}
      >
        <Redo2 size={16} />
      </button>
    </div>
  );
}
