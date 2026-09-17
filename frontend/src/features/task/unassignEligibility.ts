// 배정 해제가 열리는가 — 화면의 **사전 비활성** 판정 단일 지점.
//
// [@design API-259] [@design AC-1123] [@design SCREEN-012] [@design ADR-069]
//
// ★거부 대상은 **검수 대기 · 검수 중 · 승인 셋뿐**이다. 배정이 그 워크플로의 전제라, 풀면
//   검수 흐름이 주인 없는 상태가 된다.
//
// ★★**반려는 여기 들지 않는다.** 워크플로가 작업자에게 되돌아온 상태라 그 작업 자체를 접을 수
//   있어야 한다. 「반려도 검수 축이니 함께 막자」로 넓히면 잘못 배정한 반려 건이 영영 풀리지
//   않고 **「반려 → 해제 → 제외」 경로가 통째로 막힌다** — 그 경로가 막히면 검수 흐름에 한 번
//   들어갔던 영상은 어떤 방법으로도 목록에서 정리할 수 없다.
//   서버도 같은 이유로 「검수 단계 코드 전체」를 그대로 쓰지 않고 두 상태에서만 파생한다
//   (`AssignmentWorkStatus.unassignBlockingCodes`).
//
// ⚠ **두 축은 같은 집합을 다른 표기로 가리킨다.** 서버는 워크플로 상태 코드
//   (`PENDING`·`IN_REVIEW`·`APPROVED`)로 판정하고, 화면은 그것이 **이미 접혀 내려온 표시 상태**로
//   판정한다 — 서버가 `PENDING`·`IN_REVIEW` 를 `REVIEW_PENDING` 하나로, `APPROVED` 를
//   `COMPLETED` 로 접어 응답하기 때문이다. 화면이 원래 코드를 다시 유도하지 않는다.
//
// ⚠ 화면의 사전 비활성과 서버 거부는 **서로를 대신하지 못한다** — 여기서 막는 것은 「눌러서
//   물리쳐진 뒤에야 아는」 동선을 없애는 편의이고, 실제 강제는 해제 창구가 그대로 한다.

import type { RowStatus } from './statusLabels';

/**
 * 배정 해제가 막히는 표시 상태.
 *
 * ⚠ `REJECTED` 를 넣지 말 것 — 위 머리말 참조. 넣는 순간 반려 건의 정리 경로가 사라진다.
 */
export const UNASSIGN_BLOCKED_STATUSES = ['REVIEW_PENDING', 'COMPLETED'] as const;

/**
 * 비활성 사유 문구 — 해제 창구가 물리칠 때 내려보내는 문장과 **같은 말**이다
 * (`ErrorCode.ASSIGNMENT_SUBMITTED`). 화면과 서버가 다른 말을 하면 사용자가 같은 상황을
 * 두 번 다르게 배운다.
 */
export const UNASSIGN_BLOCKED_REASON = '검수에 들어간 배정은 해제할 수 없습니다.';

/** 이 표시 상태의 배정은 해제가 막히는가. */
export function isUnassignBlocked(status: RowStatus): boolean {
  return (UNASSIGN_BLOCKED_STATUSES as readonly string[]).includes(status);
}
