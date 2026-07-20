// Phase 4 — mergeAutoLabels(오토라벨/추적 병합) 액션 테스트.

import { beforeEach, describe, expect, it } from 'vitest';

import { useLabelStore } from '@/stores/useLabelStore';

import type { Label } from '../types';

function bbox(id: string, l: number, t: number, r: number, b: number, extra?: Partial<Label>): Label {
  return {
    id,
    frameNo: 1,
    classId: 1,
    className: 'person',
    source: 'MANUAL',
    shape: { type: 'BBOX', left: l, top: t, right: r, bottom: b },
    ...extra,
  };
}

function detected(className: string, l: number, t: number, r: number, b: number): Label {
  return {
    id: '', // BE lblSn=null 정규화 결과 — 병합 시 클라 id 로 대체돼야 함
    frameNo: 1,
    classId: 1,
    className,
    source: 'AUTO_YOLO',
    shape: { type: 'BBOX', left: l, top: t, right: r, bottom: b },
  };
}

describe('useLabelStore.mergeAutoLabels', () => {
  beforeEach(() => {
    useLabelStore.getState().reset();
  });

  it('오토라벨_결과가_기존_라벨을_유지한채_작업본에_병합된다', () => {
    const store = useLabelStore.getState();
    store.setLabels([bbox('m1', 0, 0, 10, 10, { className: 'car' })]);
    const added = useLabelStore.getState().mergeAutoLabels([detected('person', 500, 500, 600, 600)]);
    const labels = useLabelStore.getState().labels;
    expect(added).toBe(1);
    // 기존 라벨 보존 + 신규 병합.
    expect(labels).toHaveLength(2);
    expect(labels.some((l) => l.id === 'm1')).toBe(true);
    expect(labels.some((l) => l.className === 'person')).toBe(true);
  });

  it('병합된_라벨은_클라이언트_id를_받아_id충돌이_없다', () => {
    const store = useLabelStore.getState();
    store.setLabels([]);
    useLabelStore
      .getState()
      .mergeAutoLabels([detected('person', 0, 0, 10, 10), detected('car', 100, 100, 110, 110)]);
    const labels = useLabelStore.getState().labels;
    const ids = labels.map((l) => l.id);
    // 빈 id('') 가 남아있지 않고 모두 유니크.
    expect(ids.every((id) => id.length > 0)).toBe(true);
    expect(new Set(ids).size).toBe(ids.length);
    // serverId 는 미저장이라 undefined.
    expect(labels.every((l) => l.serverId === undefined)).toBe(true);
  });

  it('병합된_라벨은_전부_dirty로_표시된다', () => {
    useLabelStore.getState().setLabels([]);
    useLabelStore.getState().mergeAutoLabels([detected('person', 0, 0, 10, 10)]);
    const { labels, dirtyLabels } = useLabelStore.getState();
    expect(dirtyLabels.size).toBe(1);
    expect(dirtyLabels.has(labels[0].id)).toBe(true);
  });

  it('같은_클래스_중복은_스킵되어_병합_0건이고_dirty_변화없음', () => {
    useLabelStore.getState().setLabels([bbox('m1', 0, 0, 100, 100, { className: 'person' })]);
    const added = useLabelStore.getState().mergeAutoLabels([detected('person', 1, 1, 100, 100)]);
    expect(added).toBe(0);
    expect(useLabelStore.getState().labels).toHaveLength(1);
    expect(useLabelStore.getState().dirtyLabels.size).toBe(0);
  });

  it('병합은_단일_undo_스냅샷으로_한번에_취소된다', () => {
    useLabelStore.getState().setLabels([bbox('m1', 0, 0, 10, 10, { className: 'car' })]);
    useLabelStore
      .getState()
      .mergeAutoLabels([detected('person', 500, 500, 600, 600), detected('dog', 700, 700, 800, 800)]);
    expect(useLabelStore.getState().labels).toHaveLength(3);
    useLabelStore.getState().undo();
    expect(useLabelStore.getState().labels).toHaveLength(1);
    expect(useLabelStore.getState().labels[0].id).toBe('m1');
  });

  it('폴리곤_검출은_폴리곤_라벨로_병합된다', () => {
    useLabelStore.getState().setLabels([]);
    const pg: Label = {
      id: '',
      frameNo: 1,
      classId: 1,
      className: 'person',
      source: 'AUTO_SAM2',
      shape: { type: 'POLYGON', points: [0, 0, 10, 0, 10, 10, 0, 10] },
    };
    useLabelStore.getState().mergeAutoLabels([pg]);
    const labels = useLabelStore.getState().labels;
    expect(labels).toHaveLength(1);
    expect(labels[0].shape.type).toBe('POLYGON');
  });
});
