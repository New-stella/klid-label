// Phase 7 — useLabelStore.activeLabelId 테스트.
//
// LabelSidebar 클릭 또는 단축키 1~9 가 라벨을 활성화.
// 신규 BBOX/Polygon 그리기 시 활성 라벨의 classId/className 가 사용된다.

import { beforeEach, describe, expect, it } from 'vitest';

import { useLabelStore } from '@/stores/useLabelStore';

describe('useLabelStore.activeLabelId', () => {
  beforeEach(() => {
    useLabelStore.getState().reset();
  });

  it('초기값_null', () => {
    expect(useLabelStore.getState().activeLabelId).toBeNull();
  });

  it('setActiveLabelId_로_변경', () => {
    useLabelStore.getState().setActiveLabelId(42);
    expect(useLabelStore.getState().activeLabelId).toBe(42);
  });

  it('setActiveLabelId_null_로_초기화', () => {
    useLabelStore.getState().setActiveLabelId(7);
    useLabelStore.getState().setActiveLabelId(null);
    expect(useLabelStore.getState().activeLabelId).toBeNull();
  });

  it('reset_으로_null_복귀', () => {
    useLabelStore.getState().setActiveLabelId(5);
    useLabelStore.getState().reset();
    expect(useLabelStore.getState().activeLabelId).toBeNull();
  });
});
