// 사용자 도메인 API — BE: /api/v1/users, /users/workers

import { apiClient } from '@/lib/api/client';
import type { PageResponse } from '@/lib/api/types';

import type { User, UserListParams } from './types';

export interface WorkerSummary {
  id: number;
  name: string;
  email?: string;
  active: boolean;
}

/** 배정 가능한 작업자 목록 (간단 응답). */
export function listWorkers() {
  return apiClient.get<WorkerSummary[]>('/users/workers').then((r) => r.data);
}

/**
 * 보안: keyword/role/active 모두 axios params로만 전달 (XSS/Injection 방지).
 */
export function listUsers(params: UserListParams) {
  return apiClient
    .get<PageResponse<User>>('/users', { params })
    .then((r) => r.data);
}

export function getUser(id: number) {
  return apiClient.get<User>(`/users/${id}`).then((r) => r.data);
}
