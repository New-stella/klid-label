// 라벨명 → 라벨 마스터 PK(LS_LABEL.LABEL_ID) 역인덱스.
//
// 용도는 **하나뿐**이다: BE 응답이 `labelId` 를 담지 않고 라벨명만 돌려주는 경로
// (SAM2 Track — `Sam2TrackResponseDto.TrackedItem` 는 `label` 문자열만 갖는다) 에서
// 작업본에 병합할 라벨의 마스터 연결을 복구한다.
//
// ⚠ 마스터 연결이 끊긴 채(labelId=null) 저장되면 BE 가 `LS_LABEL` 을 조인하지 못해
//    재조회 응답의 color/label 이 null 이 되고, 캔버스·객체 패널이 마스터 색 대신
//    trackId 해시색으로 떨어진다(= "저장하면 색이 바뀐다").
//
// ⚠ 이 파일은 색상·표시명을 판정하지 않는다 — 그 단일 진실원은
//    `utils/labelColor.getLabelDisplayColor` / `utils/labelDisplayName` 이다.

import type { LabelMaster } from '../api/labelMaster';

/**
 * 활성(useYn='Y') 라벨 마스터에서 이름이 정확히 일치하는 라벨의 PK 를 찾는다.
 *
 * 이름은 운영자가 바꿀 수 있고 유일성이 보장되지 않으므로 **추측하지 않는다** —
 * 일치 항목이 0건이거나 2건 이상이면 null 을 돌려주고 호출측은 기존 동작
 * (마스터 미연결)으로 남는다. 잘못된 labelId 를 붙이면 다른 분류로 저장된다.
 */
export function resolveLabelIdByName(
  masters: ReadonlyArray<LabelMaster> | undefined,
  name: string | null | undefined,
): number | null {
  if (!masters || masters.length === 0) return null;
  if (typeof name !== 'string' || name.length === 0) return null;
  const matched = masters.filter((m) => m.useYn === 'Y' && m.name === name);
  return matched.length === 1 ? matched[0].labelId : null;
}
