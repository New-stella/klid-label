// 이슈 타입/상태 한글 라벨 — IssueCard / IssueThreadPanel 공용 단일 정의.
// (중복 정의 제거 — code-reviewer #3)

import { resolveDisplayName } from '@/lib/displayName';

import { ISSUE_STATUS, ISSUE_TYPE, type IssueStatus, type IssueType } from './types';

export const ISSUE_TYPE_LABEL: Record<IssueType, string> = {
  [ISSUE_TYPE.REJECTION]: '반려',
  [ISSUE_TYPE.INQUIRY]: '검수자 확인 요청',
};

export const ISSUE_STATUS_LABEL: Record<IssueStatus, string> = {
  [ISSUE_STATUS.OPEN]: '열림',
  [ISSUE_STATUS.ANSWERED]: '답변됨',
  [ISSUE_STATUS.RESOLVED]: '해소됨',
};

// 작성자 역할 한글 라벨 — 화면에 코드값(WORKER/REVIEWER)을 그대로 노출하지 않는다.
// UI 호칭은 '검수자'로 통일(프로젝트 역할 정의).
const ISSUE_AUTHOR_ROLE_LABEL: Record<string, string> = {
  WORKER: '작업자',
  REVIEWER: '검수자',
  // 관리자도 문의·댓글을 쓴다 — 응답 계약의 작성자 역할 값에 포함돼 있다. 빠뜨리면 아래 폴백이
  // 코드값을 그대로 노출해 「홍길동 (ADMIN)」이 된다(이 표의 존재 이유가 그것을 막는 것이다).
  ADMIN: '관리자',
};

/** 매핑에 없는 코드는 원문 폴백 — 새 역할이 생겨도 빈칸이 되지 않게 한다. */
export function issueAuthorRoleLabel(roleCd: string): string {
  return ISSUE_AUTHOR_ROLE_LABEL[roleCd] ?? roleCd;
}

/**
 * 작성자 표기 — "{이름} ({역할})". 댓글 작성자와 스레드 작성자가 **같은 표기**를 쓴다.
 *
 * 폴백 순서:
 * - 이름이 없으면 사번으로 폴백한다(빈칸 금지).
 * - 사번마저 없으면 역할만 표기한다.
 * - **역할을 해석할 수 없으면(`null`/공백) 이름만** 표기한다 — 스레드 축은 BE 가 사용자 역할 매핑에서
 *   역할을 읽으므로 퇴사·미배정·비숫자 사번에서 `null` 이 온다. 그때 `"홍길동 ()"` 처럼 빈 괄호를
 *   남기면 값이 유실된 것처럼 보인다. 댓글 축은 작성 시점 역할이 컬럼에 박혀 있어 이 분기에 걸리지
 *   않지만, 두 축이 같은 함수를 쓰도록 여기서 함께 처리한다(표기 로직을 복제하면 한쪽만 갱신된다).
 * - 둘 다 없으면 빈 문자열 — 호출부가 falsy 로 판단해 미표시한다.
 */
export function issueAuthorLabel(
  name: string | null | undefined,
  userNo: string | null | undefined,
  roleCd: string | null | undefined,
): string {
  const who = resolveDisplayName(name, userNo) ?? '';
  const trimmedRole = typeof roleCd === 'string' ? roleCd.trim() : '';
  if (!trimmedRole) {
    return who;
  }
  const role = issueAuthorRoleLabel(trimmedRole);
  return who ? `${who} (${role})` : role;
}
