// Phase 3 (R6 개별 잠금) — lockedLabelIds(Set) + toggleLabelLock. 세션 전용, 영속 안 함.
// 잠금 라벨은 updateLabel/removeLabel no-op(dirty/undo 미변화).
import { beforeEach, describe, expect, it } from 'vitest';

import { useLabelStore } from '@/stores/useLabelStore';
import type { Label } from '@/features/label/types';

function bbox(id: string): Label {
  return {
    id,
    frameNo: 1,
    classId: 1,
    className: 'car',
    source: 'MANUAL',
    shape: { type: 'BBOX', left: 0, top: 0, right: 10, bottom: 10 },
  };
}

beforeEach(() => {
  useLabelStore.getState().reset();
});

describe('useLabelStore — 라벨 개별 잠금(R6)', () => {
  it('초기_lockedLabelIds_빈집합', () => {
    expect(useLabelStore.getState().lockedLabelIds.size).toBe(0);
  });

  it('객체행_잠금_토글시_lockedLabelIds가_갱신된다', () => {
    useLabelStore.getState().toggleLabelLock('a');
    expect(useLabelStore.getState().lockedLabelIds.has('a')).toBe(true);
    useLabelStore.getState().toggleLabelLock('a');
    expect(useLabelStore.getState().lockedLabelIds.has('a')).toBe(false);
  });

  it('잠금_토글은_기존Set을_변형하지_않고_새Set을_만든다', () => {
    const before = useLabelStore.getState().lockedLabelIds;
    useLabelStore.getState().toggleLabelLock('a');
    const after = useLabelStore.getState().lockedLabelIds;
    expect(after).not.toBe(before);
    expect(before.has('a')).toBe(false); // 원본 Set 미변형
  });

  it('잠금_라벨은_updateLabel이_no_op이다', () => {
    useLabelStore.getState().setLabels([bbox('a')]);
    useLabelStore.getState().toggleLabelLock('a');
    useLabelStore.getState().updateLabel('a', { className: 'person' });
    const s = useLabelStore.getState();
    expect(s.labels[0].className).toBe('car'); // 미변경
    expect(s.dirtyLabels.has('a')).toBe(false); // dirty 미변화
    expect(s.undoStack.length).toBe(0); // undo 미변화
  });

  it('잠금_라벨은_removeLabel이_no_op이다', () => {
    useLabelStore.getState().setLabels([bbox('a')]);
    useLabelStore.getState().toggleLabelLock('a');
    useLabelStore.getState().removeLabel('a');
    const s = useLabelStore.getState();
    expect(s.labels).toHaveLength(1);
    expect(s.dirtyLabels.has('a')).toBe(false);
    expect(s.undoStack.length).toBe(0);
  });

  it('비잠금_라벨의_정상_편집은_막지_않는다', () => {
    useLabelStore.getState().setLabels([bbox('a'), bbox('b')]);
    useLabelStore.getState().toggleLabelLock('a');
    useLabelStore.getState().updateLabel('b', { className: 'person' });
    const s = useLabelStore.getState();
    expect(s.labels.find((l) => l.id === 'b')?.className).toBe('person');
    expect(s.dirtyLabels.has('b')).toBe(true);
  });

  it('잠금_라벨은_selectLabel이_no_op이다', () => {
    useLabelStore.getState().setLabels([bbox('a')]);
    useLabelStore.getState().toggleLabelLock('a');
    useLabelStore.getState().selectLabel('a');
    // 잠금 라벨은 어떤 진입점에서도 선택 불가 — selectedLabelId 미변경(null 유지).
    expect(useLabelStore.getState().selectedLabelId).toBeNull();
  });

  it('비잠금_라벨_선택은_정상_잠금해제후_선택가능', () => {
    useLabelStore.getState().setLabels([bbox('a')]);
    // 비잠금 → 정상 선택.
    useLabelStore.getState().selectLabel('a');
    expect(useLabelStore.getState().selectedLabelId).toBe('a');
    // 선택 해제(null)는 잠금과 무관하게 항상 허용.
    useLabelStore.getState().selectLabel(null);
    expect(useLabelStore.getState().selectedLabelId).toBeNull();
  });

  it('setLabels시_lockedLabelIds가_초기화된다', () => {
    useLabelStore.getState().toggleLabelLock('a');
    useLabelStore.getState().setLabels([bbox('b')]);
    expect(useLabelStore.getState().lockedLabelIds.size).toBe(0);
  });

  it('reset시_lockedLabelIds가_초기화된다', () => {
    useLabelStore.getState().toggleLabelLock('a');
    useLabelStore.getState().reset();
    expect(useLabelStore.getState().lockedLabelIds.size).toBe(0);
  });
});
