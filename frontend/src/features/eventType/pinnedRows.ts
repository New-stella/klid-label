// 저장 직후 조건에서 벗어난 행을 목록에 붙잡아 두기 위한 병합 규칙.
//
// <h3>왜 필요한가</h3>
// 「오토라벨 보류만 보기」를 켠 상태에서 표시명을 저장하면 그 유형의 표시명 그룹이 쪼개져
// <b>연결 상태가 바뀌고</b>, 다음 조회에서 그 행이 조건에서 빠질 수 있다. 그때 행이 화면에서
// 그냥 사라지면 방금 무엇을 저장했는지 확인할 자리가 없어진다 — 사양 SCREEN-038 이
// "저장 뒤에도 그 행을 그대로 두고 사라지게 하지 않는다"로 못박은 지점이다.
//
// <h3>왜 클라이언트 필터가 아닌가</h3>
// 이것은 <b>거르기를 화면에서 다시 하는 것이 아니다</b>. 거르기는 그대로 서버가 전체를 대상으로
// 수행하고, 여기서는 <b>방금 사용자가 저장한 행 하나</b>만 결과에 되돌려 놓는다. 화면이 모집단을
// 다시 만들면 아직 받지 않은 보류 유형이 빠지지만, 이 병합은 이미 보고 있던 행을 유지할 뿐이라
// 모집단을 줄이지 않는다.
//
// @design SCREEN-038

import type { EventTypeAdminItem } from './adminApi';

/** 붙잡아 둘 행 + 사라지기 직전에 있던 자리. */
export interface PinnedEventTypeRow {
  row: EventTypeAdminItem;
  /** 저장 시점의 목록 내 위치 — 되돌려 놓을 때 그 자리에 다시 끼운다. */
  index: number;
}

/**
 * 서버 목록에 고정 행을 병합한다.
 *
 * - 서버 목록에 이미 있는 유형은 <b>서버 값이 이긴다</b>(고정 행은 무시). 서버가 판정한 연결
 *   상태가 최신이고, 고정 행이 든 값은 저장 응답이라 연결 상태가 비어 있기 때문이다.
 * - 서버 목록에서 빠진 유형만 원래 자리에 다시 끼운다. 자리가 목록 길이를 넘으면 끝에 붙인다.
 */
export function mergePinnedRows(
  serverRows: readonly EventTypeAdminItem[],
  pinned: readonly PinnedEventTypeRow[],
): EventTypeAdminItem[] {
  if (pinned.length === 0) return [...serverRows];

  const merged = [...serverRows];
  // 앞자리부터 끼워야 뒤 항목의 자리 계산이 밀리지 않는다.
  for (const { row, index } of [...pinned].sort((a, b) => a.index - b.index)) {
    if (merged.some((r) => r.evntTypeCd === row.evntTypeCd)) continue;
    merged.splice(Math.max(0, Math.min(index, merged.length)), 0, row);
  }
  return merged;
}

/** 고정 목록에 행을 넣거나 갱신한다(같은 유형이 두 번 끼지 않게 한다). */
export function upsertPinnedRow(
  pinned: readonly PinnedEventTypeRow[],
  row: EventTypeAdminItem,
  index: number,
): PinnedEventTypeRow[] {
  const rest = pinned.filter((p) => p.row.evntTypeCd !== row.evntTypeCd);
  return [...rest, { row, index }];
}
