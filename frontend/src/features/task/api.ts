// 작업 배정 도메인 API — BE: /api/v1/assignments

import { apiClient } from '@/lib/api/client';
import type { PageResponse } from '@/lib/api/types';

import type {
  AssignTaskRequest,
  Assignment,
  ReassignTaskRequest,
  Task,
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

export function assignTask(body: AssignTaskRequest) {
  return apiClient.post<Assignment>('/assignments', body).then((r) => r.data);
}

/**
 * 재배정은 PATCH (BE plan 정합 — POST 아님).
 */
export function reassignTask(id: number, body: ReassignTaskRequest) {
  return apiClient.patch<Assignment>(`/assignments/${id}`, body).then((r) => r.data);
}
