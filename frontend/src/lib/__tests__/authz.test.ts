// 역할 인가 판정 — 계층 단일 진실원(`@/lib/authz`)의 값·동작 고정. [@design ROLE-004] [@design AC-125]
//
// ★두 축을 **짝으로** 둔다.
//   ① 동작 축 — 어떤 역할이 어떤 자리에 들어가는가.
//   ② 값 고정 축 — 상속 선언이 정확히 무엇인가.
//   ①만 두면 계층을 넓히는 변경(예: 작업자를 상속에 끼우는 것)이 「관리자가 검수자 자리에
//   들어간다」는 단언을 그대로 통과시킨다. ②가 그 확대를 직접 잡는다.

import { describe, expect, it } from 'vitest';

import { Role } from '@/lib/api/types';
import {
  ROLE_INHERITS,
  inheritedRoles,
  isKnownRole,
  roleSatisfies,
  roleSatisfiesAny,
} from '@/lib/authz';

describe('역할 상속 선언 (값 고정)', () => {
  it('상속은_관리자에서_검수자로_가는_한_줄뿐이다', () => {
    // 넓히면 인가가 샌다 — 작업자를 넣으면 작업자 전용 자리에 상위 역할이 흘러들고,
    // 포털 회원을 넣으면 채널 격리가 역할 축으로 뚫린다.
    expect(
      Object.fromEntries(
        Object.entries(ROLE_INHERITS).map(([k, v]) => [k, [...(v ?? [])].sort()]),
      ),
    ).toEqual({ ADMIN: ['REVIEWER'] });
  });

  it('역할_값의_집합이_고정돼_있다', () => {
    // 값이 늘거나 이름이 바뀌면 아래 판정들이 조용히 의미를 잃는다. 서버 축과의 대조는
    // 별도 계약 시험(`src/test/roleHierarchyContract.test.ts`)이 맡는다.
    expect([...Object.values(Role)].sort()).toEqual([
      'ADMIN',
      'PORTAL_USER',
      'REVIEWER',
      'WORKER',
    ]);
  });

  it('전이_폐포는_자기_자신을_포함하지_않는다', () => {
    expect(inheritedRoles(Role.ADMIN)).toEqual([Role.REVIEWER]);
    expect(inheritedRoles(Role.REVIEWER)).toEqual([]);
    expect(inheritedRoles(Role.WORKER)).toEqual([]);
    expect(inheritedRoles(Role.PORTAL_USER)).toEqual([]);
  });
});

describe('roleSatisfies — 「같거나 물려받는가」', () => {
  it('관리자는_검수자_자리에_들어간다', () => {
    expect(roleSatisfies(Role.ADMIN, Role.REVIEWER)).toBe(true);
  });

  it('관리자도_작업자_전용_자리에는_들어가지_못한다', () => {
    // ★계층은 한 단계뿐이다. 여기가 참이 되면 검수 제출처럼 작업자에게만 열린 자리에
    //   상위 역할이 흘러들어, 그 화면들이 「누가 하는 일인지」를 구분하지 못하게 된다.
    expect(roleSatisfies(Role.ADMIN, Role.WORKER)).toBe(false);
  });

  it('관리자는_포털_회원_자리에_들어가지_못한다', () => {
    // 채널이 다르다 — 참이 되면 채널 격리를 역할 축으로 우회하는 길이 생긴다.
    expect(roleSatisfies(Role.ADMIN, Role.PORTAL_USER)).toBe(false);
  });

  it('같은_역할은_언제나_참이다', () => {
    for (const r of Object.values(Role)) {
      expect(roleSatisfies(r, r)).toBe(true);
    }
  });

  it('계층은_한_방향이라_검수자가_관리자_자리에_오르지_못한다', () => {
    expect(roleSatisfies(Role.REVIEWER, Role.ADMIN)).toBe(false);
    expect(roleSatisfies(Role.WORKER, Role.ADMIN)).toBe(false);
    expect(roleSatisfies(Role.PORTAL_USER, Role.ADMIN)).toBe(false);
  });

  it('작업자와_포털_회원은_검수자_자리에_들어가지_못한다', () => {
    expect(roleSatisfies(Role.WORKER, Role.REVIEWER)).toBe(false);
    expect(roleSatisfies(Role.PORTAL_USER, Role.REVIEWER)).toBe(false);
  });

  it('역할이_없으면_거짓이다', () => {
    expect(roleSatisfies(null, Role.REVIEWER)).toBe(false);
    expect(roleSatisfies(undefined, Role.REVIEWER)).toBe(false);
  });

  it('요구_역할이_없으면_거짓이다', () => {
    // fail-closed — 요구 조건을 못 읽었다는 것은 「아무나 된다」가 아니다.
    expect(roleSatisfies(Role.ADMIN, null)).toBe(false);
    expect(roleSatisfies(Role.ADMIN, undefined)).toBe(false);
  });
});

describe('roleSatisfiesAny — 허용 목록', () => {
  it('목록_중_하나만_만족해도_참이다', () => {
    expect(roleSatisfiesAny(Role.ADMIN, [Role.REVIEWER, Role.WORKER])).toBe(true);
    expect(roleSatisfiesAny(Role.WORKER, [Role.REVIEWER, Role.WORKER])).toBe(true);
  });

  it('관리자는_검수자_전용_목록도_통과한다', () => {
    expect(roleSatisfiesAny(Role.ADMIN, [Role.REVIEWER])).toBe(true);
  });

  it('검수자는_관리자_전용_목록을_통과하지_못한다', () => {
    expect(roleSatisfiesAny(Role.REVIEWER, [Role.ADMIN])).toBe(false);
    expect(roleSatisfiesAny(Role.WORKER, [Role.ADMIN])).toBe(false);
    expect(roleSatisfiesAny(Role.PORTAL_USER, [Role.ADMIN])).toBe(false);
  });

  it('빈_목록은_아무에게도_열려_있지_않다', () => {
    // 목록을 비우는 실수가 전면 개방이 되지 않게 한다.
    expect(roleSatisfiesAny(Role.ADMIN, [])).toBe(false);
    expect(roleSatisfiesAny(Role.REVIEWER, [])).toBe(false);
  });

  it('역할이_없으면_거짓이다', () => {
    expect(roleSatisfiesAny(null, [Role.REVIEWER])).toBe(false);
    expect(roleSatisfiesAny(undefined, [Role.WORKER])).toBe(false);
  });
});

describe('isKnownRole', () => {
  it('아는_값만_참이다', () => {
    for (const r of Object.values(Role)) {
      expect(isKnownRole(r)).toBe(true);
    }
  });

  it('모르는_값과_비문자열은_거짓이다', () => {
    expect(isKnownRole('SUPER_ADMIN')).toBe(false);
    expect(isKnownRole('admin')).toBe(false);
    expect(isKnownRole('')).toBe(false);
    expect(isKnownRole(null)).toBe(false);
    expect(isKnownRole(42)).toBe(false);
  });
});
