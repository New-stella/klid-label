// Phase 7 — 신규 BBOX/Polygon 그리기 시 적용할 라벨 마스터 결정.
//
// 우선순위:
//   1. activeLabelId 가 useLabelMasters 응답의 활성(useYn=Y) 라벨에 존재 → 해당 라벨
//   2. activeLabelId 가 null/없는 라벨 → sortNo 최소 활성 라벨 (자동 default)
//   3. labelMasters 가 비어 있음 → null (캔버스 핸들러가 신규 BBOX 생성 거부)

import type { LabelMaster } from '../../api/labelMaster';

export function resolveDefaultLabel(
  masters: LabelMaster[],
  activeLabelId: number | null,
): LabelMaster | null {
  const active = masters.filter((m) => m.useYn === 'Y');
  if (active.length === 0) return null;

  if (activeLabelId !== null) {
    const found = active.find((m) => m.labelId === activeLabelId);
    if (found) return found;
  }

  // sortNo asc — 안정 정렬 위해 labelId 보조 정렬.
  const sorted = [...active].sort((a, b) => {
    if (a.sortNo !== b.sortNo) return a.sortNo - b.sortNo;
    return a.labelId - b.labelId;
  });
  return sorted[0] ?? null;
}
