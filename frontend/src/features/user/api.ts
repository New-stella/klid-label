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

/**
 * 사용자 수정 요청 — 활성/비활성(useYn) + 역할(role). 두 필드 모두 선택.
 * 값은 화이트리스트 리터럴 유니온으로 타입 단계에서 허용 값 외 전송을 차단.
 */
export interface UserUpdatePayload {
  useYn?: 'Y' | 'N';
  role?: 'REVIEWER' | 'WORKER' | 'PORTAL_USER';
}

/**
 * 사용자 활성/비활성 + 역할 변경 (REVIEWER 전용).
 * BE 가 @Pattern 화이트리스트로 useYn(Y|N)·role(REVIEWER|WORKER|PORTAL_USER) 검증.
 */
export function updateUser(userNo: number, payload: UserUpdatePayload) {
  return apiClient.patch(`/users/${userNo}`, payload).then((r) => r.data);
}
