// 사용자 도메인 API — BE: /api/v1/users, /users/workers

import { adminSessionHeaders } from '@/features/adminSession/api';
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
 * 사용자 수정 요청 — <b>역할과 표시 이름</b> 두 축. 역할 값은 화이트리스트 리터럴 유니온으로
 * 타입 단계에서 허용 값 외 전송을 차단.
 * 활성/비활성(useYn)은 관제서버 책임으로 이관되어 저작도구에서 변경하지 않는다.
 *
 * <p>★<b>두 축 모두 선택이며 「보내지 않으면 미변경」이 규약이다</b>([@design API-004]).
 * 그래서 호출부는 <b>바꾼 축만</b> 담는다 — 안 바꾼 축을 현재 값으로 채워 보내면 서버가 그것을
 * 「변경 요청」으로 받아, 이름만 고친 저장이 역할 축 판정(마지막 관리자 409)에 걸린다.
 */
export interface UserUpdatePayload {
  role?: 'ADMIN' | 'REVIEWER' | 'WORKER' | 'PORTAL_USER';
  /**
   * 바꿀 표시 이름. 서버가 공백·제어문자를 걷어낸 값으로 판정하며, 그 뒤 비거나 저장 폭을
   * 넘으면 <b>잘라 담지 않고 400</b> 이다. 화면은 보내기 전에 같은 규칙으로 먼저 막는다
   * (`./displayNameRules`).
   */
  userNm?: string;
}

/**
 * 사용자 역할·표시 이름 변경 (관리자 + 관리자 유효창). [@design API-004] [@design ADR-046]
 *
 * BE 가 @Pattern 화이트리스트로 role(ADMIN|REVIEWER|WORKER|PORTAL_USER) 검증.
 *
 * <p>이 창구의 쓰기는 운영·관리 성격이라 관리자 역할 <b>위에</b> 관리자 단기 유효창이 가산된다.
 * ★유효창은 <b>창구 전체</b>에 걸린다 — 역할을 바꾸는 저장도, <b>표시 이름만 고치는 저장도</b>
 * 같다([@design SCREEN-024] v37). 유효창이 없거나 끝났으면 서버가 403 으로 거부한다 — 화면은 그
 * 거부를 조용히 삼키지 말고 만료 사실을 알린 뒤 재확인을 받는다.
 *
 * <p>★<b>조회 창구(`listUsers`·`getUser`·`listWorkers`)에는 이 헤더를 붙이지 않는다.</b>
 * 특히 {@link listWorkers} 는 작업 배정 화면이 읽으므로, 「일관성」을 이유로 조회에까지 요건을
 * 얹으면 배정 흐름이 통째로 끊긴다.
 */
export function updateUser(
  userNo: number,
  payload: UserUpdatePayload,
  adminSessionToken?: string,
) {
  // 응답은 갱신된 프로필(`UserProfileResponse`)이다. `channel` 은 이 경로에서 빈 문자열이다
  // (피조회 사용자의 요청 컨텍스트가 없어 BE 가 채우지 않는다).
  return apiClient
    .patch<UserProfile>(`/users/${userNo}`, payload, {
      headers: adminSessionHeaders(adminSessionToken),
    })
    .then((r) => r.data);
}
