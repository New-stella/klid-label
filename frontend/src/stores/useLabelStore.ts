import { create } from 'zustand';

import type { Label, ToolType } from '@/features/label/types';
import { ToolType as ToolTypeEnum } from '@/features/label/types';

interface UndoSnapshot {
  labels: Label[];
}

interface LabelState {
  // 도구/선택
  activeTool: ToolType;
  selectedLabelId: string | null;
  /**
   * Phase 7: 라벨 사이드바에서 선택된 활성 라벨 마스터 ID.
   * 신규 BBOX/Polygon 그리기 시 이 라벨의 classId/className 이 자동 적용된다.
   * null 이면 OverlayLayer 가 sortNo 최소 활성 라벨로 fallback.
   */
  activeLabelId: number | null;

  // 라벨 데이터 (현재 프레임)
  labels: Label[];
  /** 변경 후 저장 전 dirty 플래그 (라벨 ID 집합) */
  dirtyLabels: Set<string>;

  // 캔버스 상태
  zoom: number;
  panX: number;
  panY: number;

  // Undo/Redo
  undoStack: UndoSnapshot[];
  redoStack: UndoSnapshot[];

  // Actions
  setActiveTool: (tool: ToolType) => void;
  selectLabel: (id: string | null) => void;
  setActiveLabelId: (id: number | null) => void;
  setLabels: (labels: Label[]) => void;
  addLabel: (label: Label) => void;
  updateLabel: (id: string, patch: Partial<Label>) => void;
  removeLabel: (id: string) => void;
  setZoom: (zoom: number) => void;
  setPan: (x: number, y: number) => void;
  undo: () => void;
  redo: () => void;
  clearDirty: () => void;
  reset: () => void;
}

const MIN_ZOOM = 0.1;
const MAX_ZOOM = 8;
const MAX_UNDO = 50;

function clampZoom(z: number): number {
  return Math.min(Math.max(z, MIN_ZOOM), MAX_ZOOM);
}

function snapshot(labels: Label[]): UndoSnapshot {
  return { labels: labels.map((l) => ({ ...l, shape: { ...l.shape } })) };
}

export const useLabelStore = create<LabelState>((set, get) => ({
  activeTool: ToolTypeEnum.SELECT,
  selectedLabelId: null,
  activeLabelId: null,
  labels: [],
  dirtyLabels: new Set<string>(),
  zoom: 1,
  panX: 0,
  panY: 0,
  undoStack: [],
  redoStack: [],

  setActiveTool: (tool) => set({ activeTool: tool }),
  selectLabel: (id) => set({ selectedLabelId: id }),
  setActiveLabelId: (id) => set({ activeLabelId: id }),

  setLabels: (labels) =>
    set({
      labels,
      dirtyLabels: new Set<string>(),
      undoStack: [],
      redoStack: [],
      selectedLabelId: null,
    }),

  addLabel: (label) => {
    const prev = get().labels;
    const dirty = new Set(get().dirtyLabels);
    dirty.add(label.id);
    const undoStack = [...get().undoStack, snapshot(prev)].slice(-MAX_UNDO);
    set({ labels: [...prev, label], dirtyLabels: dirty, undoStack, redoStack: [] });
  },

  updateLabel: (id, patch) => {
    const prev = get().labels;
    const dirty = new Set(get().dirtyLabels);
    dirty.add(id);
    const undoStack = [...get().undoStack, snapshot(prev)].slice(-MAX_UNDO);
    const next = prev.map((l) => (l.id === id ? { ...l, ...patch } : l));
    set({ labels: next, dirtyLabels: dirty, undoStack, redoStack: [] });
  },

  removeLabel: (id) => {
    const prev = get().labels;
    const dirty = new Set(get().dirtyLabels);
    dirty.add(id);
    const undoStack = [...get().undoStack, snapshot(prev)].slice(-MAX_UNDO);
    const next = prev.filter((l) => l.id !== id);
    set({
      labels: next,
      dirtyLabels: dirty,
      undoStack,
      redoStack: [],
      selectedLabelId: get().selectedLabelId === id ? null : get().selectedLabelId,
    });
  },

  setZoom: (zoom) => set({ zoom: clampZoom(zoom) }),
  setPan: (x, y) => set({ panX: x, panY: y }),

  undo: () => {
    const { undoStack, labels, redoStack } = get();
    if (undoStack.length === 0) return;
    const prev = undoStack[undoStack.length - 1];
    set({
      labels: prev.labels,
      undoStack: undoStack.slice(0, -1),
      redoStack: [...redoStack, snapshot(labels)].slice(-MAX_UNDO),
    });
  },

  redo: () => {
    const { redoStack, labels, undoStack } = get();
    if (redoStack.length === 0) return;
    const next = redoStack[redoStack.length - 1];
    set({
      labels: next.labels,
      redoStack: redoStack.slice(0, -1),
      undoStack: [...undoStack, snapshot(labels)].slice(-MAX_UNDO),
    });
  },

  clearDirty: () => set({ dirtyLabels: new Set<string>() }),

  reset: () =>
    set({
      activeTool: ToolTypeEnum.SELECT,
      selectedLabelId: null,
      activeLabelId: null,
      labels: [],
      dirtyLabels: new Set<string>(),
      zoom: 1,
      panX: 0,
      panY: 0,
      undoStack: [],
      redoStack: [],
    }),
}));
