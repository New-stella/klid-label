import { create } from 'zustand';
import type { MarkItem, MarkingMode } from './types';

interface MarkingState {
  mode: MarkingMode;
  intervalFrames: number;
  localMarks: MarkItem[];
  selectedMarkIndex: number | null;

  setMode: (mode: MarkingMode) => void;
  setIntervalFrames: (frames: number) => void;
  addMark: (mark: MarkItem) => void;
  removeMark: (index: number) => void;
  selectMark: (index: number | null) => void;
  removeSelectedMark: () => void;
  clearMarks: () => void;
  reset: () => void;
}

export const useMarkingStore = create<MarkingState>((set, get) => ({
  mode: 'MANUAL',
  intervalFrames: 300,
  localMarks: [],
  selectedMarkIndex: null,

  setMode: (mode) => set({ mode, localMarks: [], selectedMarkIndex: null }),
  setIntervalFrames: (frames) => set({ intervalFrames: Math.max(1, frames) }),

  addMark: (mark) => {
    const prev = get().localMarks;
    const exists = prev.some((m) => m.frameIndex === mark.frameIndex);
    if (exists) return;
    const sorted = [...prev, mark].sort((a, b) => a.frameIndex - b.frameIndex);
    set({ localMarks: sorted });
  },

  removeMark: (index) => {
    const prev = get().localMarks;
    set({
      localMarks: prev.filter((_, i) => i !== index),
      selectedMarkIndex: null,
    });
  },

  selectMark: (index) => set({ selectedMarkIndex: index }),

  removeSelectedMark: () => {
    const idx = get().selectedMarkIndex;
    if (idx === null) return;
    get().removeMark(idx);
  },

  clearMarks: () => set({ localMarks: [], selectedMarkIndex: null }),
  reset: () =>
    set({
      mode: 'MANUAL',
      intervalFrames: 300,
      localMarks: [],
      selectedMarkIndex: null,
    }),
}));
