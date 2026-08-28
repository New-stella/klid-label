// 회귀 가드 — 역할 표시명 표 3종이 «관리자»를 안다. [@design ROLE-004] [@design API-102]
// [@design API-103] [@design API-104] [@design DS-001]
//
// 배경: 이 표들은 전부 **원문 폴백**을 갖는다(모르는 코드도 빈칸이 되지 않게). 그래서 관리자가
//   빠져도 오류 없이 「홍길동 (ADMIN)」처럼 **코드값이 그대로 화면에 노출**된다 — 표의 존재
//   이유가 정확히 그것을 막는 것이라, 폴백이 있다는 사실이 누락을 조용하게 만든다.
//
// ★값 고정 축을 함께 둔다. 「비어 있지 않다」만 보면 라벨끼리 뒤바뀌어도 통과한다.

import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';

import { RoleBadge } from '@/components/common/RoleBadge';
import { issueAuthorLabel, issueAuthorRoleLabel } from '@/features/review/issueLabels';
import { Role } from '@/lib/api/types';
import { ROLE_COLOR, ROLE_COLOR_FALLBACK, ROLE_LABEL } from '@/lib/roleDisplay';

describe('GNB·접근 거부 화면의 역할 표시 (roleDisplay)', () => {
  it('네_역할의_표시명이_고정돼_있다', () => {
    expect(ROLE_LABEL.ADMIN).toBe('관리자');
    expect(ROLE_LABEL.REVIEWER).toBe('검수자');
    expect(ROLE_LABEL.WORKER).toBe('작업자');
    expect(ROLE_LABEL.PORTAL_USER).toBe('포털');
  });

  it('모든_역할이_표시명과_색을_갖는다', () => {
    for (const role of Object.values(Role)) {
      expect(ROLE_LABEL[role], `${role} 표시명이 없다`).toBeTruthy();
      expect(ROLE_COLOR[role], `${role} 색이 없다`).toBeTruthy();
    }
  });

  it('관리자와_검수자는_서로_다른_색을_쓴다', () => {
    // 계층으로 이어져 있다고 같은 색을 주면 배지가 두 역할을 구분하지 못한다.
    expect(ROLE_COLOR.ADMIN).not.toBe(ROLE_COLOR.REVIEWER);
    expect(ROLE_COLOR.ADMIN).not.toBe(ROLE_COLOR_FALLBACK);
  });
});

describe('사용자 목록의 역할 배지 (RoleBadge)', () => {
  it('관리자_배지에_코드값이_아니라_한글이_뜬다', () => {
    render(<RoleBadge role={Role.ADMIN} />);
    expect(screen.getByText('관리자')).toBeInTheDocument();
    expect(screen.queryByText('ADMIN')).toBeNull();
  });

  it('미배정과_구분된다', () => {
    // 관리자가 매핑에 없으면 원문 폴백으로 떨어지는데, 그 폴백은 미배정과 다른 경로라
    // 「그래도 뭔가 뜨니까 괜찮다」로 넘어가기 쉽다 — 두 상태를 함께 고정한다.
    const { unmount } = render(<RoleBadge role={Role.ADMIN} />);
    expect(screen.queryByText('미배정')).toBeNull();
    unmount();

    render(<RoleBadge role={null} />);
    expect(screen.getByText('미배정')).toBeInTheDocument();
  });
});

describe('이슈 작성자 역할 라벨 (issueLabels)', () => {
  it('관리자가_한글로_표기된다', () => {
    // 응답 계약의 작성자 역할 값에 관리자가 포함돼 있다 — 빠지면 코드값이 그대로 노출된다.
    expect(issueAuthorRoleLabel('ADMIN')).toBe('관리자');
    expect(issueAuthorRoleLabel('REVIEWER')).toBe('검수자');
    expect(issueAuthorRoleLabel('WORKER')).toBe('작업자');
  });

  it('작성자_표기에_코드값이_새지_않는다', () => {
    expect(issueAuthorLabel('홍길동', '9001', 'ADMIN')).toBe('홍길동 (관리자)');
  });

  it('여전히_모르는_코드는_원문으로_폴백한다', () => {
    // ★폴백을 없앤 것이 아니다 — 새 역할이 또 생겨도 빈칸이 되지 않아야 한다.
    expect(issueAuthorRoleLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
  });
});
