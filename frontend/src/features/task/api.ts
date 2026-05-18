// 작업 배정 도메인 API — BE: /api/v1/assignments

import { apiClient } from '@/lib/api/client';
import type { PageResponse } from '@/lib/api/types';

import type {
  AssignTaskRequest,
  Assignment,
  AssignmentHistory,
  ReassignTaskRequest,
  Task,
  TaskBoardItem,
  TaskBoardParams,
  TaskListParams,
} from './types';

/**
 * 보안: axios가 자동 URL 인코딩 (XSS/Injection 방지).
 * 사용자 입력은 params/body로만 전달 — 문자열 직접 연결 금지.
 */
export function listTasks(params: TaskListParams) {
  return apiClient
    .get<PageResponse<Task>>('/assignments', { params })
    .then((r) => r.data);
}

/**
 * SCR-TASK-001 REVIEWER 통합 작업 목록 — BE: /v1/tasks/board.
 *
 * 처리 완료 영상(LS_DATA_RAW.DATA_STTS_CD) 을 BE 페이징으로 응답하고
 * LABELER/REVIEWER 배정을 LEFT JOIN 방식으로 enrich 한다.
 * 미배정 영상도 함께 노출되며 task 측 필드는 null 로 응답된다.
 *
 * 보안: REVIEWER 권한 필수 (BE @PreAuthorize + Service requireReviewer 이중 가드).
 */
export function listTaskBoard(params: TaskBoardParams) {
  return apiClient
    .get<PageResponse<TaskBoardItem>>('/tasks/board', { params })
    .then((r) => r.data);
}

export function assignTask(body: AssignTaskRequest) {
  return apiClient.post<Assignment>('/assignments', body).then((r) => r.data);
}

/**
 * 재배정은 PATCH (BE plan 정합 — POST 아님).
 */
export function reassignTask(id: number, body: ReassignTaskRequest) {
  return apiClient.patch<Assignment>(`/assignments/${id}`, body).then((r) => r.data);
}

/**
 * 배정 이력 조회 — REVIEWER 만 접근(서버측 @PreAuthorize 검증).
 * URL 파라미터는 axios 가 안전하게 인코딩한다.
 */
export function getAssignmentHistory(assignmentId: number) {
  return apiClient
    .get<AssignmentHistory[]>(`/assignments/${assignmentId}/history`)
    .then((r) => r.data);
}
