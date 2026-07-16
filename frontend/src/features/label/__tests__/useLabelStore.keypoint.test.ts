// Phase 3: useLabelStore snapshot 딥클론 — keypoints 배열까지 깊게 복사.
// 얕은 복사({...shape})면 keypoints 배열이 스냅샷과 공유되어 undo 오염 발생.

import { beforeEach, describe, expect, it } from 'vitest';

import { useLabelStore } from '@/stores/useLabelStore';

import type { KeypointShape, Label } from '../types';

function makeKps(): { x: number; y: number; v: number }[] {
  return Array.from({ length: 17 }, (_, i) => ({ x: i, y: i, v: 2 }));
}

const kpLabel: Label = {
  id: 'kp',
  frameNo: 1,
  classId: 1,
  className: 'person',
  source: 'MANUAL',
  shape: { type: 'KEYPOINT', keypoints: makeKps() },
};

describe('useLabelStore — keypoints 딥클론 (undo 복원)', () => {
  beforeEach(() => {
    useLabelStore.getState().reset();
  });

  it('useLabelStore_undo가_keypoints_배열까지_복원', () => {
    const store = useLabelStore.getState();
    store.addLabel(kpLabel);
    // 변경을 일으켜 KEYPOINT 라벨의 스냅샷이 undoStack 에 push 되게 함.
    store.updateLabel('kp', { className: 'changed' });

    // 라이브 라벨의 keypoints 배열을 제자리(in-place) 변형 (버그성 드래그 시뮬).
    const live = useLabelStore.getState().labels[0].shape as KeypointShape;
    live.keypoints[0].x = 999;
    live.keypoints[0].y = 999;

    // undo → 스냅샷 복원. 딥클론이면 원좌표(0,0), 얕은복사면 오염된 999.
    store.undo();
    const restored = useLabelStore.getState().labels[0].shape as KeypointShape;
    expect(restored.keypoints[0].x).toBe(0);
    expect(restored.keypoints[0].y).toBe(0);
  });

  it('useLabelStore_기존_BBOX_POLYGON_undo_회귀없음', () => {
    const store = useLabelStore.getState();
    const bbox: Label = {
      id: 'b',
      frameNo: 1,
      classId: 1,
      className: 'car',
      source: 'MANUAL',
      shape: { type: 'BBOX', left: 0, top: 0, right: 10, bottom: 10 },
    };
    store.addLabel(bbox);
    store.updateLabel('b', { shape: { type: 'BBOX', left: 5, top: 5, right: 20, bottom: 20 } });
    store.undo();
    const restored = useLabelStore.getState().labels[0].shape;
    expect(restored.type).toBe('BBOX');
    if (restored.type === 'BBOX') {
      expect(restored.right).toBe(10);
    }
  });
});
