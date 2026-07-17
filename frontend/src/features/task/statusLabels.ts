// 작업(task) 문맥 상태 라벨 — TaskListPage / DashboardPage 공용.
//
// 공유 StatusBadge 의 기본 라벨(영상/증강 문맥)과 의미가 다른 상태가 있어(예: PENDING),
// task 문맥에서는 이 맵으로 label 을 오버라이드한다.
//   - PENDING = '배정 완료' (StatusBadge 기본값 '대기'와 구분)
// StatusBadge 기본값은 영상/증강 문맥용이라 변경하지 않는다.

import type { AssignmentStatus } from './types';

export type RowStatus = AssignmentStatus | 'UNASSIGNED';

export const TASK_STATUS_LABEL: Record<RowStatus, string> = {
  UNASSIGNED: '미배정',
  PENDING: '배정 완료',
  IN_PROGRESS: '작업중',
  REVIEW_PENDING: '검수요청',
  COMPLETED: '완료',
  REJECTED: '반려',
};
