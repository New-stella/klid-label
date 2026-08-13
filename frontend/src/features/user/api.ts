// 사용자 도메인 API — BE: /api/v1/users, /users/workers

import { apiClient } from '@/lib/api/client';
import type { PageResponse } from '@/lib/api/types';

import type { User, UserListParams, UserProfile } from './types';

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

/**
 * 사용자 프로필 단건 조회 (REVIEWER 전용).
 *
 * ⚠ 응답은 목록의 `User` 가 **아니라** `UserProfile`(BE `UserProfileResponse`)이다 —
 * FE 호환 alias(`id`/`loginId`/`name`/`email`)가 없고 원본 컬럼명(`userNo`/`userId`/
 * `userNm`/`userEmail`)만 온다. 여기를 `User` 로 선언하면 화면이 존재하지 않는 필드를
 * 읽으면서도 컴파일은 통과한다(= 값이 조용히 `undefined`).
 */
export function getUser(userNo: number) {
  return apiClient.get<UserProfile>(`/users/${userNo}`).then((r) => r.data);
}

/**
 * 사용자 수정 요청 — 역할(role)만. 값은 화이트리스트 리터럴 유니온으로
 * 타입 단계에서 허용 값 외 전송을 차단.
 * 활성/비활성(useYn)은 관제서버 책임으로 이관되어 저작도구에서 변경하지 않는다.
 */
export interface UserUpdatePayload {
  role?: 'REVIEWER' | 'WORKER' | 'PORTAL_USER';
}

/**
 * 사용자 역할 변경 (REVIEWER 전용).
 * BE 가 @Pattern 화이트리스트로 role(REVIEWER|WORKER|PORTAL_USER) 검증.
 */
export function updateUser(userNo: number, payload: UserUpdatePayload) {
  // 응답은 갱신된 프로필(`UserProfileResponse`)이다. `channel` 은 이 경로에서 빈 문자열이다
  // (피조회 사용자의 요청 컨텍스트가 없어 BE 가 채우지 않는다).
  return apiClient.patch<UserProfile>(`/users/${userNo}`, payload).then((r) => r.data);
}
