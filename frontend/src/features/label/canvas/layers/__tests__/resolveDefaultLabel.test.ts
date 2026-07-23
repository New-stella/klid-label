// Phase 7 — 신규 BBOX/Polygon 그리기 시 activeLabelId 결정 헬퍼.
//
// 우선순위:
//   1. activeLabelId 가 useLabelMasters 응답에 존재 → 해당 라벨
//   2. activeLabelId 가 null/없는 라벨 → sortNo 최소 활성 라벨
//   3. labelMasters 가 비어 있음 → null (BBOX 생성 거부)

import { describe, expect, it } from 'vitest';

import { resolveDefaultLabel } from '../resolveDefaultLabel';
import type { LabelMaster } from '../../../api/labelMaster';

const labels: LabelMaster[] = [
  { labelId: 5, name: '자전거', color: '#10B981', type: 'BBOX', sortNo: 3, useYn: 'Y', dtctTypeCd: null },
  { labelId: 1, name: '사람', color: '#EF4444', type: 'BBOX', sortNo: 1, useYn: 'Y', dtctTypeCd: null },
  { labelId: 2, name: '차량', color: '#3B82F6', type: 'BBOX', sortNo: 2, useYn: 'Y', dtctTypeCd: null },
];

describe('resolveDefaultLabel', () => {
  it('activeLabelId_가_지정되면_해당_라벨_반환', () => {
    const r = resolveDefaultLabel(labels, 2);
    expect(r?.labelId).toBe(2);
    expect(r?.name).toBe('차량');
  });

  it('activeLabelId_가_null_이면_sortNo_최소_라벨_반환', () => {
    const r = resolveDefaultLabel(labels, null);
    expect(r?.labelId).toBe(1);
    expect(r?.name).toBe('사람');
  });

  it('activeLabelId_가_master_에_없으면_sortNo_최소_라벨로_fallback', () => {
    const r = resolveDefaultLabel(labels, 999);
    expect(r?.labelId).toBe(1);
  });

  it('labelMasters_가_비어있으면_null', () => {
    const r = resolveDefaultLabel([], null);
    expect(r).toBeNull();
  });

  it('useYn=N_라벨은_제외', () => {
    const withInactive: LabelMaster[] = [
      { labelId: 1, name: '사람', color: '#EF4444', type: 'BBOX', sortNo: 1, useYn: 'N', dtctTypeCd: null },
      { labelId: 2, name: '차량', color: '#3B82F6', type: 'BBOX', sortNo: 2, useYn: 'Y', dtctTypeCd: null },
    ];
    const r = resolveDefaultLabel(withInactive, null);
    expect(r?.labelId).toBe(2);
  });
});
