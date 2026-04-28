import { create } from 'zustand';
import type { LabelObject } from '../api/types';
import { labelColor, labelName } from '../utils/labelColors';

export type Tool = 'select' | 'bbox' | 'polygon';

interface UndoEntry {
  frame: number;
  objects: LabelObject[];
}

interface LabelStore {
  videoId: string | null;
  currentFrame: number;
  totalFrames: number;
  frames: Record<number, LabelObject[]>;
  selectedId: string | null;
  tool: Tool;
  imageSize: { width: number; height: number };
  undoStack: UndoEntry[];
  dirty: boolean;
  readOnly: boolean;

  setVideo: (id: string, totalFrames: number) => void;
  setFrame: (n: number) => void;
  setTool: (t: Tool) => void;
  setFrameLabels: (frame: number, objects: LabelObject[]) => void;
  addObject: (o: LabelObject) => void;
  updateObject: (id: string, patch: Partial<LabelObject>) => void;
  removeObject: (id: string) => void;
  removeSelected: () => void;
  selectObject: (id: string | null) => void;
  pushUndo: () => void;
  undo: () => void;
  markClean: () => void;
  setReadOnly: (r: boolean) => void;
}

const MAX_UNDO = 30;

export const useLabelStore = create<LabelStore>((set, get) => ({
  videoId: null,
  currentFrame: 0,
  totalFrames: 60,
  frames: {},
  selectedId: null,
  tool: 'select',
  imageSize: { width: 640, height: 360 },
  undoStack: [],
  dirty: false,
  readOnly: false,

  setVideo: (id, totalFrames) => {
    set({
      videoId: id,
      totalFrames,
      currentFrame: 0,
      frames: {},
      selectedId: null,
      tool: 'select',
      undoStack: [],
      dirty: false,
    });
  },

  setFrame: (n) => {
    set({ currentFrame: n, selectedId: null });
  },

  setTool: (t) => {
    set({ tool: t });
  },

  setFrameLabels: (frame, objects) => {
    set((state) => ({
      frames: { ...state.frames, [frame]: objects },
    }));
  },

  addObject: (o) => {
    if (get().readOnly) {
      console.warn('[LabelStore] addObject blocked: readOnly mode');
      return;
    }
    const { currentFrame, frames } = get();
    get().pushUndo();
    const current = frames[currentFrame] ?? [];
    set((state) => ({
      frames: { ...state.frames, [currentFrame]: [...current, o] },
      selectedId: o.id,
      dirty: true,
    }));
  },

  updateObject: (id, patch) => {
    if (get().readOnly) {
      console.warn('[LabelStore] updateObject blocked: readOnly mode');
      return;
    }
    const { currentFrame, frames } = get();
    get().pushUndo();
    const current = frames[currentFrame] ?? [];
    const updated = current.map((obj) => {
      if (obj.id !== id) return obj;
      const merged = { ...obj, ...patch };
      // Sync color/name if labelCode changed
      if (patch.labelCode && patch.labelCode !== obj.labelCode) {
        merged.color = labelColor(patch.labelCode);
        merged.labelName = labelName(patch.labelCode);
      }
      return merged;
    });
    set((state) => ({
      frames: { ...state.frames, [currentFrame]: updated },
      dirty: true,
    }));
  },

  removeObject: (id) => {
    if (get().readOnly) {
      console.warn('[LabelStore] removeObject blocked: readOnly mode');
      return;
    }
    const { currentFrame, frames, selectedId } = get();
    get().pushUndo();
    const current = frames[currentFrame] ?? [];
    set((state) => ({
      frames: { ...state.frames, [currentFrame]: current.filter((o) => o.id !== id) },
      selectedId: selectedId === id ? null : selectedId,
      dirty: true,
    }));
  },

  removeSelected: () => {
    const { selectedId } = get();
    if (selectedId) {
      get().removeObject(selectedId);
    }
  },

  selectObject: (id) => {
    set({ selectedId: id });
  },

  pushUndo: () => {
    const { currentFrame, frames, undoStack } = get();
    const objects = frames[currentFrame] ?? [];
    const newStack = [
      ...undoStack.slice(-(MAX_UNDO - 1)),
      { frame: currentFrame, objects: [...objects] },
    ];
    set({ undoStack: newStack });
  },

  undo: () => {
    const { undoStack } = get();
    if (undoStack.length === 0) return;
    const last = undoStack[undoStack.length - 1];
    set((state) => ({
      undoStack: state.undoStack.slice(0, -1),
      frames: { ...state.frames, [last.frame]: last.objects },
      selectedId: null,
      dirty: true,
    }));
  },

  markClean: () => {
    set({ dirty: false });
  },

  setReadOnly: (r) => {
    set({ readOnly: r });
  },
}));
