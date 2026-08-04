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
};

/** 매핑에 없는 코드는 원문 폴백 — 새 역할이 생겨도 빈칸이 되지 않게 한다. */
export function issueAuthorRoleLabel(roleCd: string): string {
  return ISSUE_AUTHOR_ROLE_LABEL[roleCd] ?? roleCd;
}

/**
 * 작성자 표기 — "{이름} ({역할})". 이름이 없으면 사번으로 폴백한다(빈칸 금지).
 * 사번마저 없으면 역할만 표기한다.
 */
export function issueAuthorLabel(
  name: string | null | undefined,
  userNo: string | null | undefined,
  roleCd: string,
): string {
  const who = resolveDisplayName(name, userNo) ?? '';
  const role = issueAuthorRoleLabel(roleCd);
  return who ? `${who} (${role})` : role;
}
