import { useEffect, useMemo } from 'react';

import { useLabelStore } from '@/stores/useLabelStore';

import { PORTAL_HIDDEN_TOOLS } from '../types';

import { useLabelMasters } from './useLabelMasters';
import {
  SHORTCUT_KEYMAP,
  comboSignature,
  type ShortcutBinding,
} from './labelingKeymap';

export interface ShortcutHandlers {
  /** W — 첫 프레임 */
  onFirstFrame?: () => void;
  /** A / ← — 이전 프레임 */
  onPrevFrame?: () => void;
  /** S — 끝 프레임 */
  onLastFrame?: () => void;
  /** D / → — 다음 프레임 */
  onNextFrame?: () => void;
  /** Ctrl+S — 저장 */
  onSave?: () => void;
  /** E — 편집 모드 토글 */
  onToggleEdit?: () => void;
  /** T — 라벨 표시/숨김 */
  onToggleVisibility?: () => void;
  /** F — 폴리곤 점 추가 */
  onPolygonAddPoint?: () => void;
  /** Q — 폴리곤 자동완료 */
  onPolygonComplete?: () => void;
}

export interface ShortcutOptions {
  /**
   * ADR-013 — 포털 모드에서는 오토라벨/키포인트 미제공.
   * true 이면 PORTAL_HIDDEN_TOOLS(SAM_SEGMENT/TRACK/KEYPOINT) 단축키를 비활성화한다
   * (툴바 숨김과 정합, 키보드 우회 활성화 차단).
   */
  portalMode?: boolean;
}

const ZOOM_STEP = 1.2;

/**
 * 라벨링 단축키 — 관리자 매뉴얼 Rev.1.1 확정셋 (21 §21.9).
 * 키맵은 {@link SHORTCUT_KEYMAP} 단일 소스에서 파생하며, 충돌은 auditKeymapConflicts 테스트가 검증한다.
 *
 * 주요 배치:
 * - W/A/S/D: 프레임 첫/이전/끝/다음 (화살표 ←/→ 호환 유지)
 * - B/P: BBOX/Polygon · G: SAM 분할 · K: 키포인트 · **Shift+T**: SAM 추적 · Esc: 선택
 * - T: 라벨 표시/숨김 · F/Q: 폴리곤 점추가/자동완료 · R·Del: 삭제
 * - Ctrl+S: 저장 · Ctrl+Z / Ctrl+Shift+Z: undo/redo · +/-: 줌 · 1~9: 라벨 · E: 편집 토글
 *
 * IME 견고성: 문자 키는 물리 키 `e.code`(KeyB 등) 1순위 매칭으로 한글 IME/레이아웃과 무관하게 동작.
 * 보안: input/textarea/contentEditable 포커스 시 단축키 무시(텍스트 입력 보호).
 */
export function useLabelingShortcuts(
  handlers: ShortcutHandlers = {},
  options: ShortcutOptions = {},
): void {
  const { portalMode = false } = options;
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
    /** 이벤트가 바인딩과 매칭되는지 — 물리 code 우선, IME 조합 아닐 때 e.key 폴백. */
    function matches(e: KeyboardEvent, b: ShortcutBinding, composing: boolean): boolean {
      const ctrl = e.ctrlKey || e.metaKey;
      if (Boolean(b.ctrl) !== ctrl) return false;
      if (Boolean(b.shift) !== e.shiftKey) return false;
      if (e.altKey) return false;
      const codeMatch = b.code !== undefined && e.code === b.code;
      const keyMatch = !composing && e.key.toLowerCase() === b.key;
      return codeMatch || keyMatch;
    }

    function run(binding: ShortcutBinding, e: KeyboardEvent): void {
      switch (binding.id) {
        case 'tool.bbox':
        case 'tool.polygon':
        case 'tool.samSegment':
        case 'tool.keypoint':
        case 'tool.track':
        case 'tool.select':
          if (binding.tool) setActiveTool(binding.tool);
          return;
        case 'frame.first':
          handlers.onFirstFrame?.();
          return;
        case 'frame.prev':
          handlers.onPrevFrame?.();
          return;
        case 'frame.last':
          handlers.onLastFrame?.();
          return;
        case 'frame.next':
          handlers.onNextFrame?.();
          return;
        case 'label.toggleVisibility':
          handlers.onToggleVisibility?.();
          return;
        case 'polygon.addPoint':
          handlers.onPolygonAddPoint?.();
          return;
        case 'polygon.complete':
          handlers.onPolygonComplete?.();
          return;
        case 'label.delete': {
          const id = useLabelStore.getState().selectedLabelId;
          if (id) removeLabel(id);
          return;
        }
        case 'edit.toggle':
          handlers.onToggleEdit?.();
          return;
        case 'edit.save':
          handlers.onSave?.();
          return;
        case 'edit.undo':
          undo();
          return;
        case 'edit.redo':
          redo();
          return;
        case 'zoom.in':
          setZoom(useLabelStore.getState().zoom * ZOOM_STEP);
          return;
        case 'zoom.out':
          setZoom(useLabelStore.getState().zoom / ZOOM_STEP);
          return;
        case 'label.digit': {
          const idx = Number(e.key) - 1;
          const labelId = sortedLabelIds[idx];
          if (labelId !== undefined) setActiveLabelId(labelId);
          return;
        }
        default:
          return;
      }
    }

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

      // IME 조합 중에는 e.key 가 변환된 한글/'Process' 라 문자 매칭이 깨지므로 물리 code 만 신뢰.
      const composing = e.isComposing || e.key === 'Process';

      for (const binding of SHORTCUT_KEYMAP) {
        // ADR-013 — 포털 모드에서는 오토라벨/키포인트 도구 단축키 게이팅(툴바 숨김과 정합).
        if (portalMode && binding.tool && PORTAL_HIDDEN_TOOLS.includes(binding.tool)) continue;
        if (!matches(e, binding, composing)) continue;
        // Ctrl 조합(저장/undo/redo)은 브라우저 기본 동작 차단.
        if (binding.ctrl) e.preventDefault();
        run(binding, e);
        return;
      }
    }

    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, [handlers, portalMode, setActiveTool, undo, redo, removeLabel, setZoom, setActiveLabelId, sortedLabelIds]);
}

// 재-export: 키맵/충돌 감사 유틸을 훅 소비처가 함께 참조할 수 있게 한다.
export { SHORTCUT_KEYMAP, comboSignature };
