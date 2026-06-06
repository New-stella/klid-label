import { useEffect, useMemo } from 'react';

import { useLabelStore } from '@/stores/useLabelStore';

import { useLabelMasters } from './useLabelMasters';
import { ToolType } from '../types';

export interface ShortcutHandlers {
  onPrevFrame?: () => void;
  onNextFrame?: () => void;
  onSave?: () => void;
  onToggleEdit?: () => void;
}

const ZOOM_STEP = 1.2;

/**
 * UI/UX §4-6 라벨링 단축키.
 * - B: BBOX 도구 / P: POLYGON / S: SELECT / G: SAM 분할 / T: TRACK
 * - ←/→: 프레임 이동
 * - Ctrl+Z: undo / Ctrl+Shift+Z: redo
 * - Ctrl+S: 저장 (preventDefault)
 * - +/-: 줌
 * - Del/Backspace: 선택 라벨 삭제
 * - E: 편집 모드 토글 (외부 핸들러)
 *
 * 보안: input/textarea/contentEditable 포커스 상태에서는 단축키 무시 (텍스트 입력 보호).
 */
export function useLabelingShortcuts(handlers: ShortcutHandlers = {}): void {
  const setActiveTool = useLabelStore((s) => s.setActiveTool);
  const undo = useLabelStore((s) => s.undo);
  const redo = useLabelStore((s) => s.redo);
  const removeLabel = useLabelStore((s) => s.removeLabel);
  const setZoom = useLabelStore((s) => s.setZoom);
  const setActiveLabelId = useLabelStore((s) => s.setActiveLabelId);
  // 라벨 마스터는 staleTime 5분 — 호출 비용 무시. 1~9 단축키 매핑.
  const { data: labelMasters } = useLabelMasters();

  // sortNo asc 정렬된 첫 9개 라벨 — 1~9 키 매핑 대상.
  const sortedLabelIds = useMemo(() => {
    if (!labelMasters) return [] as number[];
    return [...labelMasters]
      .filter((m) => m.useYn === 'Y')
      .sort((a, b) => {
        if (a.sortNo !== b.sortNo) return a.sortNo - b.sortNo;
        return a.labelId - b.labelId;
      })
      .slice(0, 9)
      .map((m) => m.labelId);
  }, [labelMasters]);

  useEffect(() => {
    function handler(e: KeyboardEvent) {
      const target = e.target as HTMLElement | null;
      if (
        target &&
        (target.tagName === 'INPUT' ||
          target.tagName === 'TEXTAREA' ||
          target.isContentEditable)
      ) {
        return;
      }

      const meta = e.ctrlKey || e.metaKey;

      // Ctrl+S
      if (meta && e.key.toLowerCase() === 's') {
        e.preventDefault();
        handlers.onSave?.();
        return;
      }

      // Ctrl+Shift+Z = redo, Ctrl+Z = undo
      if (meta && e.key.toLowerCase() === 'z') {
        e.preventDefault();
        if (e.shiftKey) {
          redo();
        } else {
          undo();
        }
        return;
      }

      // 단일 키 (Ctrl 없는 경우)
      if (!meta && !e.altKey) {
        switch (e.key) {
          case 'b':
          case 'B':
            setActiveTool(ToolType.BBOX);
            return;
          case 'p':
          case 'P':
            setActiveTool(ToolType.POLYGON);
            return;
          case 's':
          case 'S':
            setActiveTool(ToolType.SELECT);
            return;
          case 'g':
          case 'G':
            setActiveTool(ToolType.SAM_SEGMENT);
            return;
          case 't':
          case 'T':
            setActiveTool(ToolType.TRACK);
            return;
          case 'e':
          case 'E':
            handlers.onToggleEdit?.();
            return;
          case 'ArrowLeft':
            handlers.onPrevFrame?.();
            return;
          case 'ArrowRight':
            handlers.onNextFrame?.();
            return;
          case '+':
          case '=': {
            const cur = useLabelStore.getState().zoom;
            setZoom(cur * ZOOM_STEP);
            return;
          }
          case '-':
          case '_': {
            const cur = useLabelStore.getState().zoom;
            setZoom(cur / ZOOM_STEP);
            return;
          }
          case 'Delete':
          case 'Backspace': {
            const id = useLabelStore.getState().selectedLabelId;
            if (id) removeLabel(id);
            return;
          }
          default: {
            // 단축키 1~9 — 라벨 마스터 sortNo asc N번째 활성화.
            if (e.key.length === 1 && e.key >= '1' && e.key <= '9') {
              const idx = Number(e.key) - 1;
              const labelId = sortedLabelIds[idx];
              if (labelId !== undefined) {
                setActiveLabelId(labelId);
              }
            }
            return;
          }
        }
      }
    }

    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, [handlers, setActiveTool, undo, redo, removeLabel, setZoom, setActiveLabelId, sortedLabelIds]);
}
