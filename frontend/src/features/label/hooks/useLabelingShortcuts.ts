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
 * 도구 단축키 매핑. 물리 키 `e.code`(KeyB 등)를 1순위로 매칭해 한글 IME/키보드 레이아웃과
 * 무관하게 동작하게 한다. `e.key`(라틴 'b'/'B' 등) 폴백을 함께 유지해 기존 라틴 키보드·테스트
 * 회귀를 방지한다.
 */
const TOOL_SHORTCUTS = [
  { code: 'KeyB', keys: ['b', 'B'], tool: ToolType.BBOX },
  { code: 'KeyP', keys: ['p', 'P'], tool: ToolType.POLYGON },
  { code: 'KeyS', keys: ['s', 'S'], tool: ToolType.SELECT },
  { code: 'KeyG', keys: ['g', 'G'], tool: ToolType.SAM_SEGMENT },
  { code: 'KeyT', keys: ['t', 'T'], tool: ToolType.TRACK },
] as const;

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
        // 도구 단축키 — 물리 키 e.code 1순위 매칭(IME/레이아웃 무관). 라틴 e.key 폴백.
        // IME 조합 중에는 e.key 가 변환된 한글(또는 'Process')이라 문자 매칭이 깨지지만,
        // e.code 는 물리 키이므로 도구 전환은 항상 인식된다.
        const composing = e.isComposing || e.key === 'Process';
        for (const t of TOOL_SHORTCUTS) {
          const codeMatch = e.code === t.code;
          const keyMatch = !composing && (t.keys as readonly string[]).includes(e.key);
          if (codeMatch || keyMatch) {
            setActiveTool(t.tool);
            return;
          }
        }

        // IME 조합 중에는 나머지 문자 단축키(E/줌 등)도 스킵 — 단, 위에서 도구 e.code 는 이미 처리됨.
        if (composing) return;

        switch (e.key) {
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
