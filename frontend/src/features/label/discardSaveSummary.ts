// R4·R5 — 「폐기 프레임 저장 확인」이 세는 축.
//
// 프레임 폐기는 <b>산출물에서 빠지는 결정</b>이라 저장 전에 한 번 드러낸다(SCREEN-005). 그 안내가
// 말하는 수를 여기서 정한다 — 화면이 곳곳에서 제각기 세면 안내와 실제 저장이 갈린다.
//
// <h3>무엇을 세나 — "이 저장이 실어 보내는 전환"뿐이다</h3>
// 저장 요청에 실리지 않는 프레임은 세지 않는다. 서버가 그 프레임을 어떻게 처리할지는 서버의 규약이고,
// 화면이 그것을 추정해 말하면 안내가 사실이 아니게 된다.
//
// @design SCREEN-005

import { DscdYn } from './types';

/** 저장 1건이 실어 보내는 프레임 하나의 폐기 전환. */
export interface DiscardChange {
  /**
   * 이 저장이 대조하는 기준값. `null` 은 <b>"모름"</b>이며 폐기 아님이 아니다
   * (응답이 폐기 축을 싣지 않은 경우 — {@link DscdYn} 주석 참조).
   */
  baseline: DscdYn | null;
  /** 저장이 실어 보내는 값. `null` 이면 이 저장에 폐기여부 필드가 없다(현재 값 유지 규약). */
  next: DscdYn | null;
}

export interface DiscardSaveSummary {
  /** 이번 저장으로 폐기되는 프레임 수 — 학습데이터 산출물에서 빠진다. */
  discarding: number;
  /** 이번 저장으로 복원되는 프레임 수 — 산출물로 되돌아온다. */
  restoring: number;
}

export const NO_DISCARD_CHANGE: DiscardSaveSummary = { discarding: 0, restoring: 0 };

/**
 * 이번 저장이 바꾸는 폐기 상태를 방향별로 센다.
 *
 * <p>기준값을 <b>모르면(null) 보내는 값 그대로 센다</b> — 저장은 그 값을 실제로 보내므로, 이미 같은
 * 값이었을 가능성 때문에 안내를 생략하기보다 알리는 쪽을 택한다(폐기는 산출물에서 빠지는 결정이다).
 */
export function summarizeDiscardSave(changes: readonly DiscardChange[]): DiscardSaveSummary {
  let discarding = 0;
  let restoring = 0;
  for (const { baseline, next } of changes) {
    // 폐기 축을 싣지 않는 저장(next=null)과 기준값 그대로인 저장은 변경이 아니다.
    if (next === null || next === baseline) continue;
    if (next === DscdYn.Y) discarding += 1;
    else restoring += 1;
  }
  return { discarding, restoring };
}

/** 확인을 받아야 하는 저장인가 — 변경이 없으면 끼어들지 않는다(작업 흐름 보호). */
export function hasDiscardChange(summary: DiscardSaveSummary): boolean {
  return summary.discarding + summary.restoring > 0;
}

/**
 * 안내 문장을 만든다 — <b>문구의 단일 원천</b>.
 *
 * <p>같은 저장 축을 타는 화면이 셋이라(저장 확인 모달 · 프레임 이동 가드 · 닫기 가드) 문장을
 * 각자 조립하면 같은 사실을 서로 다르게 말하게 된다. 한쪽이 0 이면 그쪽을 말하지 않는다 —
 * "0개 프레임이 되돌아옵니다"는 읽는 사람에게 아무 정보가 아니면서 문장만 흐린다.
 */
export function discardSaveSummaryText({ discarding, restoring }: DiscardSaveSummary): string {
  if (discarding > 0 && restoring > 0) {
    return `${discarding}개 프레임이 학습데이터에서 빠지고 ${restoring}개 프레임이 되돌아옵니다.`;
  }
  if (discarding > 0) return `${discarding}개 프레임이 학습데이터에서 빠집니다.`;
  if (restoring > 0) return `${restoring}개 프레임이 학습데이터로 되돌아옵니다.`;
  // 변경이 없으면 호출부가 이 안내를 렌더하지 않는다(방어적 기본값).
  return '이 저장에는 폐기 상태 변경이 없습니다.';
}

/**
 * 지우는 것이 아니라는 사실을 함께 말한다 — 사용자 오해를 직접 막는 문장이라 줄이지 않는다.
 * (폐기는 산출물·데이터마트 노출에서만 빠지는 결정이고 원본 데이터를 삭제하지 않는다.)
 */
export const DISCARD_SAVE_HELP_TEXT =
  '프레임·이미지·라벨은 지우지 않습니다. 산출물과 데이터마트 노출에서만 빠집니다.';
