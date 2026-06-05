// 이슈 타입/상태 한글 라벨 — IssueCard / IssueThreadPanel 공용 단일 정의.
// (중복 정의 제거 — code-reviewer #3)

import { ISSUE_STATUS, ISSUE_TYPE, type IssueStatus, type IssueType } from './types';

export const ISSUE_TYPE_LABEL: Record<IssueType, string> = {
  [ISSUE_TYPE.REJECTION]: '반려',
  [ISSUE_TYPE.INQUIRY]: '문의',
};

export const ISSUE_STATUS_LABEL: Record<IssueStatus, string> = {
  [ISSUE_STATUS.OPEN]: '열림',
  [ISSUE_STATUS.ANSWERED]: '답변됨',
  [ISSUE_STATUS.RESOLVED]: '해소됨',
};
