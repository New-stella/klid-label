// 사용자 도메인 타입 (BE OpenAPI alias)

import type { Channel, Role } from '@/lib/api/types';

export interface User {
  id: number;
  loginId: string;
  name: string;
  email?: string;
  /**
   * 역할. **미배정이면 `null`** 이다.
   *
   * BE `UserSummaryResponse.from` 은 LS_USER_ROLE 이 없거나 코드가 공백이면 `role` 을
   * `null` 로 내려보내고 기본값을 부여하지 않는다(관제 인계 키에 역할 클레임이 없는
   * 자동등록 사용자). 이 타입이 non-null 이던 동안 화면들은 컴파일러 도움 없이
   * `null` 을 그대로 흘려보냈고, 각자 캐스팅으로 우회해야 했다.
   *
   * ⚠ 타입은 `null` 만 선언하지만, 구 응답·목 데이터는 필드 자체가 없을 수 있으므로
   * 읽는 쪽은 `?? null` 로 `undefined` 까지 함께 좁힌다.
   */
  role: Role | null;
  active: boolean;
  createdAt: string;
  lastLoginAt?: string;
}

/**
 * 사용자 프로필 단건 — BE `UserProfileResponse`.
 *
 * ⚠ **목록(`User`)과 필드명이 다르다.** 목록 응답(`UserSummaryResponse`)은 FE 호환 alias
 * (`id`/`loginId`/`name`/`email`)를 함께 내려주지만, 단건 응답에는 alias 가 없고 BE 원본
 * 컬럼명(`userNo`/`userId`/`userNm`/`userEmail`)만 온다. 두 타입을 하나로 합치지 말 것 —
 * 합치면 실제로는 없는 필드를 있는 것처럼 읽게 된다.
 *
 * 소비 경로: `GET /v1/users/me` · `GET /v1/users/{userNo}` · `PATCH /v1/users/{userNo}`.
 */
export interface UserProfile {
  userNo: number;
  userId: string;
  userNm: string;
  /** 이메일 — 미등록이면 null. */
  userEmail: string | null;
  /** 역할. 미배정이면 `null`(기본값 부여 안 함 — `User.role` 과 동일 규약). */
  role: Role | null;
  /**
   * 진입 채널.
   *
   * ⚠ **본인 조회(`/me`)에서만 실제 값이 온다.** 관리 경로(`/{userNo}` 단건 조회·PATCH)는
   * 피조회 사용자의 요청 컨텍스트가 없어 BE 가 **빈 문자열**을 넣는다 — 이 값으로 채널을
   * 판정하지 말 것.
   */
  channel: Channel | '';
}

export interface UserListParams {
  keyword?: string;
  role?: Role;
  active?: boolean;
  page?: number;
  size?: number;
  sort?: string;
}
