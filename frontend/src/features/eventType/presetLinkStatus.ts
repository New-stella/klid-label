// 프리셋 연결 상태 — 값 집합·판정·거르기 토큰.
//
// <h3>왜 adminApi 가 아니라 별도 모듈인가</h3>
// 이 파일에는 <b>네트워크가 없다</b> — 서버가 준 토큰이 우리가 아는 값인지 보는 형태 판정과
// 상수뿐이다. HTTP 함수와 같은 파일에 두면 `vi.mock('./adminApi')` 로 통신을 막는 테스트가
// <b>판정 함수까지 함께 지워</b> 화면이 조용히 "모르는 값" 분기로 떨어진다(실제로 그 상태를
// 만들어 보고 이 파일을 갈랐다 — 표기가 통째로 '-' 가 되는데 테스트는 통신만 막았다고 믿는다).
//
// @design SCREEN-038, API-185

/**
 * 프리셋 <b>연결 상태</b> — BE `PresetLinkStatus` 1:1 미러.
 *
 * 그 이벤트유형에 오토라벨 저장 기준(프리셋)이 실제로 걸려 있는지를 서버가 판정해 내려준다.
 * ★화면은 이 값을 그대로 표기할 뿐 프리셋 유무·실효 여부를 다시 판정하지 않는다 — 재판정은 곧
 * 두 번째 진실원이라 규칙이 바뀔 때 배치와 화면이 조용히 갈라진다.
 *
 * - `LINKED`             : 프리셋이 있고 실효한다.
 * - `LINKED_EXCLUDED`    : 프리셋은 있으나 라벨이 0건 — 그 유형을 오토라벨 대상에서 뺀 <b>사람의 선언</b>.
 * - `LINKED_INEFFECTIVE` : 프리셋은 있으나 실효하지 않는다(담긴 라벨이 전부 미연결·AI 미매핑) — <b>사고</b>.
 * - `UNLINKED`           : 프리셋이 없다.
 */
export const PRESET_LINK_STATUSES = [
  'LINKED',
  'LINKED_EXCLUDED',
  'LINKED_INEFFECTIVE',
  'UNLINKED',
] as const;

export type PresetLinkStatus = (typeof PRESET_LINK_STATUSES)[number];

/**
 * 응답 문자열이 우리가 아는 연결 상태인지 확인한다.
 *
 * ⚠ 이것은 <b>재판정이 아니다</b> — 프리셋을 다시 보지 않고 서버가 준 토큰이 우리가 아는 값인지만
 * 본다. 모르는 값·미탑재(구 서버 · 수정 응답)면 어떤 상태로도 <b>추측하지 않는다</b>.
 */
export function isPresetLinkStatus(value: unknown): value is PresetLinkStatus {
  return typeof value === 'string' && (PRESET_LINK_STATUSES as readonly string[]).includes(value);
}

/**
 * <b>오토라벨 보류 전체</b>를 뜻하는 거르기 전용 값 — 응답 필드로는 나오지 않는다.
 *
 * ★화면이 `LINKED_INEFFECTIVE` 와 `UNLINKED` 를 스스로 합쳐 보내지 않는다. 무엇이 보류를
 * 유발하는지의 정의는 <b>서버가 소유</b>하며, 화면이 조합하면 보류 조건이 늘 때마다 화면도 함께
 * 고쳐야 하고 그 사이에는 새 상태의 유형이 목록에서 조용히 빠진다. 실제로 `LINKED_EXCLUDED` 는
 * 이름만 보면 "연결됨"이라 화면이 조합하면 놓치기 쉬운데, 그 유형은 보류를 유발하지 않는다.
 */
export const PRESET_LINK_WITHHELD = 'WITHHELD';

/** 목록 거르기 값 — 상태 4종 + 보류 전체. BE `PresetLinkStatusFilter` 미러. */
export type PresetLinkStatusFilter = PresetLinkStatus | typeof PRESET_LINK_WITHHELD;
