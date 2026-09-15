// 검수 점유(「지금 누가 이 영상을 보고 있나」)와 일괄 승인 선택의 **판정 단일 지점**.
//
// 화면 컴포넌트가 이 판정을 복제하지 않는다 — 목록 셀·체크칸·실행줄·확인 창이 각자 계산하면
// 같은 행을 두고 서로 다른 말을 하게 된다(체크는 되는데 「선택할 수 없음」이 함께 보이는 식).
//
// ★여기서 지키는 두 가지
//  1. **고를 수 있는가는 BE 가 정한다**(`bulkApprovable`). 화면이 점유·상태로 다시 계산하지 않는다 —
//     자격의 상태 축은 「단건 승인이 허용하는 상태」라 재검수 건(승인 상태 그대로)까지 포함하는데,
//     화면이 아는 값만으로는 재현되지 않는다. 재현하려 들면 두 번째 진실원이 생긴다.
//  2. **만료를 화면이 계산하지 않는다.** 유예가 지나 풀린 점유는 BE 가 `null` 로 내려보낸다.
//     화면이 「시작시각 + 유예」를 스스로 재면 시계가 갈릴 때 서버와 다른 말을 한다.
//
// [@design ADR-067] [@design SCREEN-018] [@design SCREEN-019] [@design API-008] [@design API-250]

import type { BatchApproveResultItem, Review } from './types';

/**
 * 한 행의 점유 상태 — 화면이 그리는 세 갈래.
 *
 * `mine` 과 `other` 를 가르는 것은 **표시 축**이다. 고를 수 있는지는 이 값이 아니라
 * {@link isSelectable} 이 정한다 — 신원 해석이 실패해도 안전 성질이 무너지지 않게 분리했다.
 */
export type ReviewClaimView =
  | { kind: 'none' }
  | { kind: 'mine' }
  | { kind: 'other'; name: string };

/** 점유자 이름을 못 읽었을 때 쓰는 표기 — 이름이 없다고 점유 자체를 숨기면 더 혼란스럽다. */
const UNKNOWN_REVIEWER_NAME = '다른 검수자';

/**
 * 로그인한 사람의 사번(숫자)을 얻는다.
 *
 * ★**숫자가 아닐 수 있다.** 관제가 발급한 토큰의 `sub` 는 로그인 아이디 문자열(`"admin"`)이라
 * `Number()` 가 `NaN` 이 된다. 그때는 `undefined` 를 돌려주고 호출부가 「내 것인지 모른다」로
 * 다루게 한다 — `NaN` 을 그대로 비교하면 항상 거짓이라 **조용히 남의 것처럼 보인다**.
 */
export function numericUserId(sub: string | undefined | null): number | undefined {
  if (sub == null || sub.trim() === '') return undefined;
  const n = Number(sub);
  return Number.isSafeInteger(n) ? n : undefined;
}

/**
 * 점유 표시 판정.
 *
 * 신원(`myUserId`)을 못 읽으면 **내 것이라고 단정하지 않고** 이름만 보인다(`other`). 그래도
 * 화면이 거짓말을 하지 않는다 — 이름은 어느 쪽이든 맞는 정보이고, 고를 수 있는지는 BE 값이
 * 따로 정하므로 **선택 가능 여부는 이 불확실성의 영향을 받지 않는다**.
 *
 * ⚠ 다만 `bulkApprovable` 이 참이면 그 점유의 주인은 **BE 판정상 나**이므로 `mine` 으로 본다 —
 * 그쪽이 신원 비교보다 믿을 만한 근거다.
 */
export function claimViewOf(
  review: Pick<Review, 'reviewingUserId' | 'reviewingUserName' | 'bulkApprovable'>,
  myUserId: number | undefined,
): ReviewClaimView {
  const holderId = review.reviewingUserId;
  const holderName = review.reviewingUserName;
  // 점유자 식별자·이름이 모두 비면 점유가 없거나 유예로 풀린 것이다(BE 가 null 로 내려보낸다).
  if (holderId == null && (holderName == null || holderName.trim() === '')) {
    return { kind: 'none' };
  }
  if (review.bulkApprovable === true) return { kind: 'mine' };
  if (myUserId !== undefined && holderId != null && holderId === myUserId) {
    return { kind: 'mine' };
  }
  return { kind: 'other', name: holderName?.trim() || UNKNOWN_REVIEWER_NAME };
}

/** 점유 칸 문구 — 없으면 `null`(칸을 비운다). */
export function claimLabel(view: ReviewClaimView): string | null {
  if (view.kind === 'none') return null;
  if (view.kind === 'mine') return '내가 검수 중';
  return `${view.name} 검수 중`;
}

/**
 * 고를 수 있는가 — **BE 가 판정한 값 하나만** 본다.
 *
 * ★이 한 줄이 이 화면의 안전장치다(`SCREEN-018`·`API-250`). 한 번도 열어 보지 않은 영상을
 * 무더기로 검수완료하는 길이 열리지 않게, 내가 점유한 건만 담긴다. 점유·상태로 이 값을
 * 대신 계산하지 말 것.
 */
export function isSelectable(review: Pick<Review, 'bulkApprovable'>): boolean {
  return review.bulkApprovable === true;
}

/**
 * 고를 수 없는 이유 — 체크칸 자리에 함께 세운다.
 *
 * 체크칸만 꺼 두면 왜 안 되는지 알 길이 없다(`SCREEN-018`). 고를 수 있는 행이면 `null`.
 */
export function unselectableReason(
  review: Pick<Review, 'reviewingUserId' | 'reviewingUserName' | 'bulkApprovable'>,
  myUserId: number | undefined,
): string | null {
  if (isSelectable(review)) return null;
  const view = claimViewOf(review, myUserId);
  if (view.kind === 'other') return `${view.name} 검수 중`;
  // 아무도 잡지 않았거나, 내가 잡았지만 그 상태로는 승인할 수 없는 경우다. 두 경우 모두
  // 다음 행동은 같다 — 그 영상을 열어 검수를 시작(또는 이어서)해야 담을 수 있다.
  return '검수 시작 후 선택할 수 있습니다';
}

/**
 * 일괄 승인 실패 한 건의 사유 문구.
 *
 * ★**서버가 보낸 문장을 그대로 싣는다.** 사유 코드로 우리 문장을 지어내지 않는다 —
 * `CONFLICT` 하나에만 「검수를 시작하지 않았다」·「다른 검수자가 검수 중이다」·「다른 사람이 먼저
 * 처리했다」·상태 전이 불가가 모두 들어와, 코드로 문장을 고르면 **결론은 맞고 사유는 거짓인**
 * 안내가 된다(그리고 시험은 그것을 잡지 못한다 — 단언이 결론만 보기 때문이다).
 *
 * 사유 코드는 **가르는 데가 아니라 식별하는 데** 쓴다(로그·집계·문구 부재 시 폴백).
 * 문구가 비어 오면 그때만 코드로 최소한의 말을 만든다.
 */
export function failureReasonText(item: BatchApproveResultItem): string {
  const reason = item.reason?.trim();
  if (reason) return reason;
  const code = item.errorCode?.trim();
  return code ? `처리하지 못했습니다 (${code})` : '처리하지 못했습니다';
}

/** 실패한 건만 추린다 — 「실패한 건만 다시 선택」이 쓰는 목록. */
export function failedItems(
  results: readonly BatchApproveResultItem[],
): BatchApproveResultItem[] {
  return results.filter((r) => !r.success);
}
