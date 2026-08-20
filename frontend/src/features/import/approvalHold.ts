// 승인 보류 축의 표시·조작 판정 — 이관 진행 상태와 **다른 축**이다.
//
// ★이관 상태(진행중·성공·실패)로 보류 여부를 대신 판단하지 않는다. 성공한 이관 가운데
//   **일부에만** 보류가 서기 때문이다(원본이라고 지정해 가져온 영상만). 상태로 대신 판단하면
//   보류가 선 영상을 골라 비식별 완료를 기록하는 자리가 화면에서 도달 불가가 된다.
//
// @design SCREEN-039
// @design API-207
// @design API-215

import type { ImportHistoryItem } from './types';

/** 보류 여부의 표시 문구 — 값이 없을 때 「보류 아님」으로 단정하지 않는다. */
export function approvalHoldText(approvalHeld: boolean | null): string {
  if (approvalHeld === true) return '보류';
  if (approvalHeld === false) return '없음';
  return '미상';
}

/**
 * 이 행에서 비식별 완료 기록을 열 수 있는가.
 *
 * ★**값이 없는 것(`null`)을 「보류 아님」으로 단정하지 않는다.** 서버는 영상이 없거나 그 영상의
 * 작업 상태 행이 없을 때 값을 비워 돌려주는데, 그것은 "보류가 없다"가 아니라 "알 수 없다"는
 * 뜻이다. 단정하면 화면이 보류를 푸는 자리를 감춰 그 영상은 영영 승인되지 못한다.
 *
 * 그래서 판정은 **거짓일 때만 닫는다** — 확실히 보류가 없다고 서버가 말한 경우에만.
 *
 * 영상 자체가 없으면(적재가 실패해 만들어지지 않은 이력) 기록할 대상이 없다. 이때 닫는 근거는
 * "보류가 없다"가 아니라 **대상 영상이 없다**는 것이며, 두 근거를 섞지 않는다.
 */
export function canRecordDeidentComplete(item: ImportHistoryItem): boolean {
  if (item.rawSn === null) return false;
  return item.approvalHeld !== false;
}
