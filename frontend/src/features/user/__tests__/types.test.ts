import { describe, expect, it } from 'vitest';

import type { User } from '@/features/user/types';
import { Role } from '@/lib/api/types';

/**
 * `User.role` nullable 회귀 가드.
 *
 * BE `UserSummaryResponse.from` 은 LS_USER_ROLE 이 없거나 코드가 공백이면 `role` 을
 * **null 로 내려보내고 기본값을 부여하지 않는다**. 공용 타입이 non-null 이던 동안에는
 * 화면들이 그 null 을 컴파일러 도움 없이 흘려보냈고, 각자 캐스팅으로 우회해야 했다.
 *
 * ⚠ 이 파일의 핵심 가드는 **컴파일 타임**이다 — 아래 리터럴은 `role: null` 을 가진
 * `User` 이므로, 타입이 non-null 로 되돌아가면 `tsc --noEmit` 이 실패한다.
 * vitest 는 타입을 검사하지 않고 트랜스파일만 하므로 런타임 단언만으로는 못 잡는다.
 */
const UNASSIGNED_USER: User = {
  id: 9,
  loginId: 'auto-registered',
  name: '자동등록 사용자',
  role: null,
  active: true,
  createdAt: '2026-01-01T00:00:00',
  // 한 번도 접속하지 않은 계정 — 등록일과 별개 축이라 폴백 값을 넣지 않는다.
  lastLoginAt: null,
};

const ASSIGNED_USER: User = {
  id: 7,
  loginId: 'hong',
  name: '홍길동',
  role: Role.WORKER,
  active: true,
  createdAt: '2026-01-01T00:00:00',
  lastLoginAt: '2026-08-16T14:30:00',
};

describe('User 타입', () => {
  it('역할_미배정은_null_로_표현된다', () => {
    expect(UNASSIGNED_USER.role).toBeNull();
  });

  it('역할이_있으면_Role_값이_그대로_들어간다', () => {
    expect(ASSIGNED_USER.role).toBe(Role.WORKER);
  });

  it('널병합으로_미배정과_배정을_같은_축에서_좁힐_수_있다', () => {
    // 화면들이 쓰는 판정 형태 — `?? null` 로 undefined(구 응답)까지 함께 좁힌다.
    const roles = [UNASSIGNED_USER, ASSIGNED_USER].map((u) => u.role ?? null);
    expect(roles).toEqual([null, Role.WORKER]);
  });
});
