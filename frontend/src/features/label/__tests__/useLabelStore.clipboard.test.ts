import { beforeEach, describe, expect, it } from 'vitest';

import { useLabelStore, PASTE_OFFSET } from '@/stores/useLabelStore';

import type { Label } from '../types';

function bbox(id: string, l: number, t: number, r: number, b: number, extra?: Partial<Label>): Label {
  return {
    id,
    serverId: Number(id) || undefined,
    frameNo: 1,
    classId: 1,
    className: 'car',
    source: 'MANUAL',
    shape: { type: 'BBOX', left: l, top: t, right: r, bottom: b },
    ...extra,
  };
}

describe('useLabelStore clipboard (복사/붙여넣기)', () => {
  beforeEach(() => {
    useLabelStore.getState().reset();
    // reset 은 clipboard 를 지우지 않으므로 명시적으로 비운다(테스트 격리).
    useLabelStore.setState({ clipboard: null });
  });

  it('전체_복사_후_클립보드에_딥클론_저장', () => {
    const store = useLabelStore.getState();
    store.setLabels([bbox('1', 0, 0, 10, 10), bbox('2', 20, 20, 30, 30)]);
    const count = store.copyLabels();
    expect(count).toBe(2);
    expect(useLabelStore.getState().clipboard?.labels).toHaveLength(2);
    // 딥클론 — 원본 shape 변형이 클립보드에 전파되지 않음
    store.updateLabel('1', { shape: { type: 'BBOX', left: 99, top: 99, right: 99, bottom: 99 } });
    const clip = useLabelStore.getState().clipboard!;
    expect((clip.labels[0].shape as { left: number }).left).toBe(0);
  });

  it('선택_복사_onlySelected_선택_라벨만', () => {
    const store = useLabelStore.getState();
    store.setLabels([bbox('1', 0, 0, 10, 10), bbox('2', 20, 20, 30, 30)]);
    store.selectLabel('2');
    const count = store.copyLabels({ onlySelected: true });
    expect(count).toBe(1);
    expect(useLabelStore.getState().clipboard?.labels[0].id).toBe('2');
  });

  it('빈_프레임_복사_no_op_0반환', () => {
    const store = useLabelStore.getState();
    store.setLabels([]);
    expect(store.copyLabels()).toBe(0);
    expect(useLabelStore.getState().clipboard).toBeNull();
  });

  it('빈_클립보드_붙여넣기_no_op_0반환', () => {
    const store = useLabelStore.getState();
    store.setLabels([bbox('1', 0, 0, 10, 10)]);
    expect(store.pasteLabels({ frameNo: 5 })).toBe(0);
    expect(useLabelStore.getState().labels).toHaveLength(1);
  });

  it('붙여넣기_신규id_serverId제거_frameNo적용_dirty', () => {
    const store = useLabelStore.getState();
    store.setLabels([bbox('1', 0, 0, 10, 10)]);
    store.copyLabels();
    // 라벨을 제거해 동일 좌표 충돌 없이 붙여넣기(offset 미적용) 검증
    store.removeLabel('1');
    store.clearDirty();
    const pasted = store.pasteLabels({ frameNo: 7 });
    expect(pasted).toBe(1);
    const labels = useLabelStore.getState().labels;
    const added = labels[labels.length - 1];
    expect(added.id).not.toBe('1');
    expect(added.serverId).toBeUndefined();
    expect(added.frameNo).toBe(7);
    expect(useLabelStore.getState().dirtyLabels.has(added.id)).toBe(true);
    // 좌표 그대로(충돌 없으므로 offset 미적용)
    expect(added.shape).toMatchObject({ left: 0, top: 0, right: 10, bottom: 10 });
  });

  it('완전동일_좌표_존재_시_offset_적용', () => {
    const store = useLabelStore.getState();
    store.setLabels([bbox('1', 0, 0, 10, 10)]);
    store.copyLabels();
    store.pasteLabels({ frameNo: 1 });
    const labels = useLabelStore.getState().labels;
    expect(labels).toHaveLength(2);
    expect(labels[1].shape).toMatchObject({
      left: PASTE_OFFSET,
      top: PASTE_OFFSET,
      right: 10 + PASTE_OFFSET,
      bottom: 10 + PASTE_OFFSET,
    });
  });

  it('크로스영상_붙여넣기_trackId_제거_좌표만_이관', () => {
    const store = useLabelStore.getState();
    store.setLabels([bbox('1', 0, 0, 10, 10, { trackId: 't1' })]);
    store.copyLabels({ sourceRawSn: 100 });
    store.removeLabel('1'); // 충돌 제거
    store.pasteLabels({ frameNo: 2, sourceRawSn: 200 });
    const labels = useLabelStore.getState().labels;
    const added = labels[labels.length - 1];
    expect(added.trackId).toBeNull();
    expect(added.className).toBe('car');
  });

  it('동일영상_붙여넣기_trackId_유지', () => {
    const store = useLabelStore.getState();
    store.setLabels([bbox('1', 0, 0, 10, 10, { trackId: 't1' })]);
    store.copyLabels({ sourceRawSn: 100 });
    store.removeLabel('1');
    store.pasteLabels({ frameNo: 2, sourceRawSn: 100 });
    const labels = useLabelStore.getState().labels;
    expect(labels[labels.length - 1].trackId).toBe('t1');
  });

  it('offset_후_이미지_경계_clamp', () => {
    const store = useLabelStore.getState();
    store.setLabels([bbox('1', 0, 0, 10, 10)]);
    store.copyLabels();
    store.pasteLabels({ frameNo: 1, imageWidth: 15, imageHeight: 15 });
    const labels = useLabelStore.getState().labels;
    // 동일좌표 → +10 offset → {10,10,20,20} → clamp(15,15) → {10,10,15,15}
    expect(labels[1].shape).toMatchObject({ left: 10, top: 10, right: 15, bottom: 15 });
  });

  it('붙여넣기_undo_통째_취소', () => {
    const store = useLabelStore.getState();
    store.setLabels([bbox('1', 0, 0, 10, 10)]);
    store.copyLabels();
    store.pasteLabels({ frameNo: 1 });
    expect(useLabelStore.getState().labels).toHaveLength(2);
    store.undo();
    expect(useLabelStore.getState().labels).toHaveLength(1);
  });

  it('클립보드는_프레임이동_setLabels에도_유지', () => {
    const store = useLabelStore.getState();
    store.setLabels([bbox('1', 0, 0, 10, 10)]);
    store.copyLabels();
    store.setLabels([bbox('2', 5, 5, 8, 8)]); // 다른 프레임 로드
    expect(useLabelStore.getState().clipboard?.labels).toHaveLength(1);
  });
});
