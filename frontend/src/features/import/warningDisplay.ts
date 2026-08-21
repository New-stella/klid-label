// 검사 알림의 **표시 묶음** — 판정이 아니다.
//
// 검사 응답의 알림은 한 목록으로 오고 그 안에 적재를 막는 사유와 막지 않는 사유가 함께 담긴다.
// 화면은 그 둘을 구분해 보여줘야 하므로(SCREEN-039 미리보기) 여기서 묶음만 정한다.
//
// ★★이것으로 적재 가능 여부를 판정하지 않는다. 판정은 응답의 `importable` 값 하나가 소유한다.
//   판정 지점을 둘로 두면 두 값이 어긋날 때 어느 쪽이 진실인지 알 수 없다. 그래서 이 파일에는
//   불리언을 돌려주는 함수가 없고, 아는 코드가 하나도 없어도 `importable` 이 참이면 화면은
//   적재를 허용한다.
//
// ★목록은 전수가 아니다 — 서버가 새 코드를 더하면 여기에 없을 수 있다. 모르는 코드는 버리지
//   않고 "알림" 쪽에 담아 사람이 볼 수 있게 한다(감추면 그 사실이 통째로 사라진다).
//
// @design SCREEN-039
// @design API-205

import type { ImportWarning } from './types';

/**
 * 적재를 막는 것으로 알려진 사유 코드 — BE `ImportWarningCode` 가운데 차단 축.
 *
 * 이 집합은 **표시 강조에만** 쓴다. 여기에 없는 코드가 적재를 막더라도 `importable` 이
 * 거짓으로 오므로 버튼은 그대로 비활성이고, 여기에 있는 코드가 있어도 `importable` 이
 * 참이면 버튼은 활성이다.
 */
const KNOWN_BLOCKING_CODES: ReadonlySet<string> = new Set([
  // 상한을 넘어 폴더를 읽지 않았다 — 프레임·라벨 수가 0 이며 적재할 수 없다.
  'SCAN_LIMIT_EXCEEDED',
  // 폴더 이름과 데이터셋 식별자가 모두 비어 이관 식별자를 만들 수 없다.
  'UNIDENTIFIABLE_DATASET',
  // 프레임이 하나도 없다 — 적재할 것이 없다.
  'NO_FRAME_FOUND',
  // 분류 식별 문자열이 대응 표에 담을 수 없는 길이라 대응을 만들 수 없다.
  'UNMAPPABLE_CATEGORY_CODE',
]);

export interface WarningGroups {
  /** 적재를 막는 것으로 알려진 알림. */
  blocking: ImportWarning[];
  /** 알리기만 하는 알림 + 아직 모르는 코드. */
  advisory: ImportWarning[];
}

/** 알림 목록을 표시용으로 두 묶음으로 가른다(순서는 서버가 준 그대로 유지한다). */
export function groupWarnings(warnings: readonly ImportWarning[] | undefined): WarningGroups {
  const blocking: ImportWarning[] = [];
  const advisory: ImportWarning[] = [];
  for (const w of warnings ?? []) {
    if (KNOWN_BLOCKING_CODES.has(w.code)) blocking.push(w);
    else advisory.push(w);
  }
  return { blocking, advisory };
}
