import { beforeEach, describe, expect, it } from 'vitest';

import { useLabelStore } from '@/stores/useLabelStore';

import type { Label } from '../types';

const sample: Label = {
  id: 'a',
  frameNo: 1,
  classId: 1,
  className: 'car',
  source: 'MANUAL',
  shape: { type: 'BBOX', left: 0, top: 0, right: 10, bottom: 10 },
};

describe('useLabelStore', () => {
  beforeEach(() => {
    useLabelStore.getState().reset();
  });

  it('Pan_Zoom_상태_useLabelStore_저장_복원', () => {
    const store = useLabelStore.getState();
    store.setZoom(2);
    store.setPan(50, 30);
    expect(useLabelStore.getState().zoom).toBe(2);
    expect(useLabelStore.getState().panX).toBe(50);
    expect(useLabelStore.getState().panY).toBe(30);

    // 클램프 검증
    store.setZoom(100);
    expect(useLabelStore.getState().zoom).toBeLessThanOrEqual(8);
    store.setZoom(0);
    expect(useLabelStore.getState().zoom).toBeGreaterThanOrEqual(0.1);
  });

  it('addLabel_시_dirtyLabels에_추가', () => {
    useLabelStore.getState().addLabel(sample);
    expect(useLabelStore.getState().dirtyLabels.has('a')).toBe(true);
    expect(useLabelStore.getState().labels).toHaveLength(1);
  });

  it('undo_redo_라벨_상태_복원', () => {
    const store = useLabelStore.getState();
    store.addLabel(sample);
    store.addLabel({ ...sample, id: 'b' });
    expect(useLabelStore.getState().labels).toHaveLength(2);

    store.undo();
    expect(useLabelStore.getState().labels).toHaveLength(1);

    store.undo();
    expect(useLabelStore.getState().labels).toHaveLength(0);

    store.redo();
    expect(useLabelStore.getState().labels).toHaveLength(1);
  });

  it('removeLabel_시_선택_해제', () => {
    const store = useLabelStore.getState();
    store.addLabel(sample);
    store.selectLabel('a');
    store.removeLabel('a');
    expect(useLabelStore.getState().selectedLabelId).toBeNull();
  });

  it('setLabels_시_dirty_undo_초기화', () => {
    const store = useLabelStore.getState();
    store.addLabel(sample);
    expect(useLabelStore.getState().dirtyLabels.size).toBe(1);

    store.setLabels([sample]);
    expect(useLabelStore.getState().dirtyLabels.size).toBe(0);
    expect(useLabelStore.getState().undoStack).toHaveLength(0);
  });
});
