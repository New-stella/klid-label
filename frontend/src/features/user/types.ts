// 사용자 도메인 타입 (BE OpenAPI alias)

import type { Role } from '@/lib/api/types';

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

export interface UserListParams {
  keyword?: string;
  role?: Role;
  active?: boolean;
  page?: number;
  size?: number;
  sort?: string;
}
