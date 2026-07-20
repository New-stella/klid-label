// R12 — 미래 프레임 추적 결과 보류 캐시(pendingTracks) stash/drain 액션 테스트.
//  - stash: 미래 프레임(srcSn)별로 추적 라벨을 누적 보류(불변성 유지).
//  - drain: 프레임 진입 시 해당 srcSn 보류분을 반환 + 보류에서 제거.
//  - reset 시 초기화 / setLabels(프레임 전환)에도 보류는 보존(핵심).

import { beforeEach, describe, expect, it } from 'vitest';

import { useLabelStore } from '@/stores/useLabelStore';

import type { Label } from '../types';

function det(className: string, l: number, t: number, r: number, b: number): Label {
  return {
    id: '',
    frameNo: 1,
    classId: 1,
    className,
    source: 'AUTO_SAM2',
    shape: { type: 'BBOX', left: l, top: t, right: r, bottom: b },
  };
}

describe('useLabelStore.pendingTracks (보류 캐시 stash/drain)', () => {
  beforeEach(() => {
    useLabelStore.getState().reset();
  });

  it('stashPendingTracks_미래프레임_라벨을_srcSn별로_보류에_저장한다', () => {
    useLabelStore.getState().stashPendingTracks({
      301: [det('person', 0, 0, 10, 10)],
      302: [det('car', 20, 20, 30, 30)],
    });
    const pending = useLabelStore.getState().pendingTracks;
    expect(pending[301]).toHaveLength(1);
    expect(pending[302]).toHaveLength(1);
    expect(pending[301][0].className).toBe('person');
  });

  it('stashPendingTracks_같은_srcSn은_덮어쓰지않고_누적한다', () => {
    useLabelStore.getState().stashPendingTracks({ 301: [det('person', 0, 0, 10, 10)] });
    useLabelStore.getState().stashPendingTracks({ 301: [det('car', 20, 20, 30, 30)] });
    expect(useLabelStore.getState().pendingTracks[301]).toHaveLength(2);
  });

  it('stashPendingTracks_보류_라벨에_유니크_클라id를_부여한다', () => {
    useLabelStore.getState().stashPendingTracks({
      301: [det('person', 0, 0, 10, 10), det('car', 1, 1, 2, 2)],
    });
    const ids = useLabelStore.getState().pendingTracks[301].map((l) => l.id);
    expect(ids.every((id) => id.length > 0)).toBe(true);
    expect(new Set(ids).size).toBe(ids.length);
  });

  it('stashPendingTracks_불변성_기존_pendingTracks_객체를_변형하지_않는다', () => {
    const before = useLabelStore.getState().pendingTracks;
    useLabelStore.getState().stashPendingTracks({ 301: [det('person', 0, 0, 10, 10)] });
    const after = useLabelStore.getState().pendingTracks;
    expect(after).not.toBe(before); // 새 객체
    expect(before[301]).toBeUndefined(); // 원본 미변형
  });

  it('drainPendingTracks_해당_srcSn_라벨을_반환하고_보류에서_제거한다', () => {
    useLabelStore.getState().stashPendingTracks({
      301: [det('person', 0, 0, 10, 10)],
      302: [det('car', 20, 20, 30, 30)],
    });
    const drained = useLabelStore.getState().drainPendingTracks(301);
    expect(drained).toHaveLength(1);
    expect(drained[0].className).toBe('person');
    // 301 은 제거, 302 는 보존.
    expect(useLabelStore.getState().pendingTracks[301]).toBeUndefined();
    expect(useLabelStore.getState().pendingTracks[302]).toHaveLength(1);
  });

  it('drainPendingTracks_보류없는_srcSn은_빈배열_반환_보류불변', () => {
    const before = useLabelStore.getState().pendingTracks;
    const drained = useLabelStore.getState().drainPendingTracks(999);
    expect(drained).toEqual([]);
    expect(useLabelStore.getState().pendingTracks).toBe(before);
  });

  it('setLabels_프레임전환에도_보류는_보존된다', () => {
    useLabelStore.getState().stashPendingTracks({ 301: [det('person', 0, 0, 10, 10)] });
    useLabelStore.getState().setLabels([]); // 프레임 전환 시뮬레이션
    expect(useLabelStore.getState().pendingTracks[301]).toHaveLength(1);
  });

  it('reset_시_pendingTracks가_초기화된다', () => {
    useLabelStore.getState().stashPendingTracks({ 301: [det('person', 0, 0, 10, 10)] });
    useLabelStore.getState().reset();
    expect(useLabelStore.getState().pendingTracks).toEqual({});
  });
});
