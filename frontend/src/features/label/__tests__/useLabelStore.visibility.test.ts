// Phase 2 (⑤ T 표시/숨김) — 라벨 가시성 세션 상태.
// hiddenLabelIds(Set) + toggleLabelVisibility. 영속 안 함(세션 전용).
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

describe('useLabelStore — 라벨 가시성(T 표시/숨김)', () => {
  it('초기_hiddenLabelIds_빈집합', () => {
    expect(useLabelStore.getState().hiddenLabelIds.size).toBe(0);
  });

  it('toggleLabelVisibility_숨김_추가', () => {
    useLabelStore.getState().toggleLabelVisibility('a');
    expect(useLabelStore.getState().hiddenLabelIds.has('a')).toBe(true);
  });

  it('toggleLabelVisibility_두번_토글하면_다시_표시', () => {
    useLabelStore.getState().toggleLabelVisibility('a');
    useLabelStore.getState().toggleLabelVisibility('a');
    expect(useLabelStore.getState().hiddenLabelIds.has('a')).toBe(false);
  });

  it('toggle는_불변성_유지_새_Set_생성', () => {
    const before = useLabelStore.getState().hiddenLabelIds;
    useLabelStore.getState().toggleLabelVisibility('a');
    const after = useLabelStore.getState().hiddenLabelIds;
    expect(after).not.toBe(before);
  });

  it('setLabels_시_hiddenLabelIds_초기화', () => {
    useLabelStore.getState().toggleLabelVisibility('a');
    useLabelStore.getState().setLabels([bbox('b')]);
    expect(useLabelStore.getState().hiddenLabelIds.size).toBe(0);
  });

  it('reset_시_hiddenLabelIds_초기화', () => {
    useLabelStore.getState().toggleLabelVisibility('a');
    useLabelStore.getState().reset();
    expect(useLabelStore.getState().hiddenLabelIds.size).toBe(0);
  });
});
