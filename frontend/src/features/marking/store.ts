import { create } from 'zustand';
import type { MarkItem, MarkingMode } from './types';

interface MarkingState {
  mode: MarkingMode;
  eventName: string;
  intervalSec: number;
  localMarks: MarkItem[];
  selectedMarkIndex: number | null;

  setMode: (mode: MarkingMode) => void;
  setEventName: (name: string) => void;
  setIntervalSec: (sec: number) => void;
  addMark: (mark: MarkItem) => void;
  removeMark: (index: number) => void;
  selectMark: (index: number | null) => void;
  removeSelectedMark: () => void;
  clearMarks: () => void;
  reset: () => void;
}

export const useMarkingStore = create<MarkingState>((set, get) => ({
  mode: 'MANUAL',
  eventName: '',
  intervalSec: 5,
  localMarks: [],
  selectedMarkIndex: null,

  setMode: (mode) => set({ mode, localMarks: [], selectedMarkIndex: null }),
  setEventName: (name) => set({ eventName: name }),
  setIntervalSec: (sec) => set({ intervalSec: Math.max(1, sec) }),

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
      eventName: '',
      intervalSec: 5,
      localMarks: [],
      selectedMarkIndex: null,
    }),
}));
