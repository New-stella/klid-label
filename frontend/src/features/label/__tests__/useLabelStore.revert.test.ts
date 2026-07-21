// 라벨 변경 이력 "이 저장 되돌리기(복원)" — revertSaveEvent 액션 테스트.
//
// 저장 이벤트(changes[])를 현재 캔버스 작업본(draft)에 역적용한다.
//  - UPDATED  → 대상 라벨을 before 상태로 복원
//  - ADDED    → 해당 라벨을 작업본에서 제거
//  - DELETED  → before 내용으로 신규 라벨 추가(serverId undefined)
// 배치 전체를 단일 undo 스냅샷 1회로 묶고, 역적용 라벨을 dirty 로 일괄 표시한다.
// 변환은 api 의 snapshotToLabel(순수 함수, normalizeLabel 재사용)로 수행한다.

import { beforeEach, describe, expect, it } from 'vitest';

import { useLabelStore } from '@/stores/useLabelStore';

import { snapshotToLabel, type LabelChangeView, type LabelSnapshotView } from '../api';
import type { Label } from '../types';

function bboxLabel(
  id: string,
  serverId: number,
  l: number,
  t: number,
  r: number,
  b: number,
  extra?: Partial<Label>,
): Label {
  return {
    id,
    serverId,
    frameNo: 1,
    classId: 3,
    className: 'person',
    labelId: 3,
    source: 'MANUAL',
    shape: { type: 'BBOX', left: l, top: t, right: r, bottom: b },
    ...extra,
  };
}

function snap(
  lblTypeCd: string | null,
  labelId: number | null,
  labelNm: string | null,
  points: number[][] | null,
): LabelSnapshotView {
  return { lblTypeCd, labelId, labelNm, pointCn: points === null ? null : JSON.stringify(points) };
}

function change(
  changeKind: LabelChangeView['changeKind'],
  lblSn: number | null,
  labelName: string | null,
  before: LabelSnapshotView | null,
  after: LabelSnapshotView | null,
): LabelChangeView {
  return { lblSn, changeKind, labelName, before, after };
}

describe('useLabelStore.revertSaveEvent', () => {
  beforeEach(() => {
    useLabelStore.getState().reset();
  });

  it('UPDATED_되돌리기는_대상라벨을_이전값으로_복원한다', () => {
    useLabelStore.getState().setLabels([bboxLabel('x', 100, 5, 5, 20, 20)]);
    const ch = change(
      'UPDATED',
      100,
      'person',
      snap('BBOX', 3, 'person', [[0, 0], [10, 10]]),
      snap('BBOX', 3, 'person', [[5, 5], [20, 20]]),
    );
    const res = useLabelStore.getState().revertSaveEvent([ch], 1, snapshotToLabel);
    expect(res.reverted).toBe(1);
    expect(res.skipped).toBe(0);
    const lbl = useLabelStore.getState().labels.find((l) => l.serverId === 100);
    expect(lbl?.shape).toEqual({ type: 'BBOX', left: 0, top: 0, right: 10, bottom: 10 });
  });

  it('ADDED_되돌리기는_해당_라벨을_작업본에서_제거한다', () => {
    useLabelStore.getState().setLabels([bboxLabel('a', 200, 0, 0, 10, 10, { className: 'car' })]);
    const ch = change('ADDED', 200, 'car', null, snap('BBOX', 5, 'car', [[0, 0], [10, 10]]));
    const res = useLabelStore.getState().revertSaveEvent([ch], 1, snapshotToLabel);
    expect(res.reverted).toBe(1);
    expect(useLabelStore.getState().labels.find((l) => l.serverId === 200)).toBeUndefined();
    expect(useLabelStore.getState().labels).toHaveLength(0);
  });

  it('DELETED_되돌리기는_이전_라벨을_신규로_추가한다', () => {
    useLabelStore.getState().setLabels([]);
    const ch = change('DELETED', null, 'dog', snap('BBOX', 7, 'dog', [[1, 1], [9, 9]]), null);
    const res = useLabelStore.getState().revertSaveEvent([ch], 3, snapshotToLabel);
    expect(res.reverted).toBe(1);
    const labels = useLabelStore.getState().labels;
    expect(labels).toHaveLength(1);
    expect(labels[0].serverId).toBeUndefined();
    expect(labels[0].className).toBe('dog');
    expect(labels[0].frameNo).toBe(3);
    expect(labels[0].shape).toEqual({ type: 'BBOX', left: 1, top: 1, right: 9, bottom: 9 });
    // 신규 추가된 라벨은 클라 임시 id 를 받아 빈 id 가 아니다.
    expect(labels[0].id.length).toBeGreaterThan(0);
  });

  it('되돌리기_배치는_undo_한번으로_취소된다', () => {
    useLabelStore
      .getState()
      .setLabels([bboxLabel('x', 100, 5, 5, 20, 20), bboxLabel('y', 200, 0, 0, 10, 10, { className: 'car' })]);
    const updated = change(
      'UPDATED',
      100,
      'person',
      snap('BBOX', 3, 'person', [[0, 0], [10, 10]]),
      snap('BBOX', 3, 'person', [[5, 5], [20, 20]]),
    );
    const added = change('ADDED', 200, 'car', null, snap('BBOX', 5, 'car', [[0, 0], [10, 10]]));
    useLabelStore.getState().revertSaveEvent([updated, added], 1, snapshotToLabel);
    // x 복원 + y 제거 → 1건.
    expect(useLabelStore.getState().labels).toHaveLength(1);
    useLabelStore.getState().undo();
    // 원본 2건으로 한 번에 복귀.
    const labels = useLabelStore.getState().labels;
    expect(labels).toHaveLength(2);
    expect(labels.find((l) => l.serverId === 100)?.shape).toEqual({
      type: 'BBOX',
      left: 5,
      top: 5,
      right: 20,
      bottom: 20,
    });
  });

  it('대상라벨이_작업본에_없으면_스킵하고_안내한다', () => {
    useLabelStore.getState().setLabels([]);
    const ch = change(
      'UPDATED',
      999,
      'person',
      snap('BBOX', 1, 'person', [[0, 0], [1, 1]]),
      snap('BBOX', 1, 'person', [[2, 2], [3, 3]]),
    );
    const res = useLabelStore.getState().revertSaveEvent([ch], 1, snapshotToLabel);
    expect(res.reverted).toBe(0);
    expect(res.skipped).toBe(1);
    // no-op — dirty/undo 변화 없음.
    expect(useLabelStore.getState().dirtyLabels.size).toBe(0);
    expect(useLabelStore.getState().undoStack).toHaveLength(0);
  });

  it('되돌리기_후_dirty로_저장필요가_표시된다', () => {
    useLabelStore.getState().setLabels([bboxLabel('x', 100, 5, 5, 20, 20)]);
    const ch = change(
      'UPDATED',
      100,
      'person',
      snap('BBOX', 3, 'person', [[0, 0], [10, 10]]),
      snap('BBOX', 3, 'person', [[5, 5], [20, 20]]),
    );
    useLabelStore.getState().revertSaveEvent([ch], 1, snapshotToLabel);
    expect(useLabelStore.getState().dirtyLabels.has('x')).toBe(true);
  });

  it('SEGMENT_before는_좌표복원_불가로_스킵된다', () => {
    useLabelStore.getState().setLabels([]);
    const ch = change('DELETED', null, 'mask', snap('SEGMENT', 1, 'mask', null), null);
    const res = useLabelStore.getState().revertSaveEvent([ch], 1, snapshotToLabel);
    expect(res.reverted).toBe(0);
    expect(res.skipped).toBe(1);
    expect(useLabelStore.getState().labels).toHaveLength(0);
  });
});
