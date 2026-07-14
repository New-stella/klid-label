import { create } from 'zustand';

import type { Label, Shape, ToolType } from '@/features/label/types';
import { ToolType as ToolTypeEnum } from '@/features/label/types';

interface UndoSnapshot {
  labels: Label[];
}

/**
 * 이미지 조절(밝기/대비/투명도) 세션 상태 (Phase 2c).
 * 영속 계층 없음 — zustand 인메모리라 새 세션/reset 시 원본값 복원.
 * - brightness: konva Brighten 필터 값 (-1..1, 0=원본)
 * - contrast: konva Contrast 필터 값 (-100..100, 0=원본)
 * - labelOpacity: 라벨 레이어 불투명도 (0..1)
 * - activeOpacity: 작업(오버레이) 레이어 불투명도 (0..1)
 */
export interface ImageAdjust {
  brightness: number;
  contrast: number;
  labelOpacity: number;
  activeOpacity: number;
}

export const DEFAULT_IMAGE_ADJUST: ImageAdjust = {
  brightness: 0,
  contrast: 0,
  labelOpacity: 1,
  activeOpacity: 1,
};

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

  // 이미지 조절 (세션 전용 — 영속 안 함)
  imageAdjust: ImageAdjust;

  /**
   * 가시성 숨김 라벨 ID 집합 (세션 전용 — 영속 안 함, Phase 2 T 표시/숨김).
   * 여기 든 라벨은 LabelsLayer 렌더에서 skip. setLabels/reset 시 초기화.
   */
  hiddenLabelIds: Set<string>;

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
  setImageAdjust: (patch: Partial<ImageAdjust>) => void;
  resetImageAdjust: () => void;
  toggleLabelVisibility: (id: string) => void;
  reset: () => void;
}

const MIN_ZOOM = 0.1;
const MAX_ZOOM = 8;
const MAX_UNDO = 50;

function clampZoom(z: number): number {
  return Math.min(Math.max(z, MIN_ZOOM), MAX_ZOOM);
}

/**
 * shape 딥클론 — 중첩 배열(POLYGON.points / KEYPOINT.keypoints)까지 새로 생성.
 * 얕은 복사({...shape})면 배열 참조가 스냅샷과 공유돼 in-place 변형 시 undo/redo 가 오염된다.
 */
function cloneShape(shape: Shape): Shape {
  if (shape.type === 'KEYPOINT') {
    return { type: 'KEYPOINT', keypoints: shape.keypoints.map((kp) => ({ ...kp })) };
  }
  if (shape.type === 'POLYGON') {
    return { type: 'POLYGON', points: [...shape.points] };
  }
  return { ...shape };
}

function snapshot(labels: Label[]): UndoSnapshot {
  return { labels: labels.map((l) => ({ ...l, shape: cloneShape(l.shape) })) };
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
  imageAdjust: { ...DEFAULT_IMAGE_ADJUST },
  hiddenLabelIds: new Set<string>(),

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
      hiddenLabelIds: new Set<string>(),
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

  setImageAdjust: (patch) =>
    set({ imageAdjust: { ...get().imageAdjust, ...patch } }),

  resetImageAdjust: () => set({ imageAdjust: { ...DEFAULT_IMAGE_ADJUST } }),

  // 불변성 유지 — 기존 Set 을 변형하지 않고 새 Set 을 생성해 구독자 재렌더 보장.
  toggleLabelVisibility: (id) => {
    const next = new Set(get().hiddenLabelIds);
    if (next.has(id)) next.delete(id);
    else next.add(id);
    set({ hiddenLabelIds: next });
  },

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
      imageAdjust: { ...DEFAULT_IMAGE_ADJUST },
      hiddenLabelIds: new Set<string>(),
    }),
}));
