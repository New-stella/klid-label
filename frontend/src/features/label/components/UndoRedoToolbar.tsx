// Undo/Redo 도구바 — 시각 버튼 + Ctrl+Z/Ctrl+Shift+Z 단축키는 useLabelingShortcuts에서 처리.

import { Redo2, Undo2 } from 'lucide-react';

import { useLabelStore } from '@/stores/useLabelStore';
import { cn } from '@/lib/cn';

/**
 * Undo/Redo 시각 버튼.
 */
export function UndoRedoToolbar() {
  const undoLength = useLabelStore((s) => s.undoStack.length);
  const redoLength = useLabelStore((s) => s.redoStack.length);
  const undo = useLabelStore((s) => s.undo);
  const redo = useLabelStore((s) => s.redo);

  const undoDisabled = undoLength === 0;
  const redoDisabled = redoLength === 0;

  return (
    <div className="flex items-center gap-1" role="toolbar" aria-label="실행 취소/다시 실행">
      <button
        type="button"
        onClick={undo}
        disabled={undoDisabled}
        aria-label="실행 취소 (Undo)"
        title="Ctrl+Z"
        className={cn(
          'flex items-center gap-1 rounded border border-border px-2 py-1 text-sub',
          undoDisabled
            ? 'cursor-not-allowed opacity-40'
            : 'bg-white text-primary hover:bg-bgLight',
        )}
      >
        <Undo2 size={14} />
        취소
      </button>
      <button
        type="button"
        onClick={redo}
        disabled={redoDisabled}
        aria-label="다시 실행 (Redo)"
        title="Ctrl+Shift+Z"
        className={cn(
          'flex items-center gap-1 rounded border border-border px-2 py-1 text-sub',
          redoDisabled
            ? 'cursor-not-allowed opacity-40'
            : 'bg-white text-primary hover:bg-bgLight',
        )}
      >
        <Redo2 size={14} />
        재실행
      </button>
    </div>
  );
}
