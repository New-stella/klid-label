// 현재 역할 배지 — 상단 헤더(GNB)와 접근 거부 화면이 **같은 표시**를 쓴다.
// [@design SHELL-001] [@design SCREEN-003] [@design ROLE-004]
//
// 무엇을 고쳤나: 두 화면이 각각 `?? Role.WORKER` 로 역할 기본값을 채우고 있었다. 역할을 아직
//   받지 못한 사람에게 **사실과 다른 역할**을 보여주는 것이고, 접근이 거부된 자리에서는 왜
//   막혔는지를 오히려 흐린다. 사양은 「미배정 + 경고 아이콘」이며 넷 중 하나로 임의로 채우지
//   않는다.
//
// ★두 화면 모두에 단언을 둔다 — 한쪽만 고치면 다른 화면에서 그대로 재현된다(실제로 같은
//   결함이 두 곳에 글자 그대로 복제돼 있었다).
//
// ★「모르는 역할」과 「역할 없음」을 같은 표시로 합치지 않는다 — 다른 사실이다. 모르는 값은
//   비우지 않고 받은 값을 그대로 중립 색으로 노출한다.

import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';

import { CurrentRoleBadge } from '@/components/common/CurrentRoleBadge';
import { ForbiddenPage } from '@/components/common/ForbiddenPage';
import { Gnb } from '@/components/layout/Gnb';
import { Role } from '@/lib/api/types';
import { ROLE_COLOR } from '@/lib/roleDisplay';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';

/** 인증 상태 자리 — 배지 판정에 쓰이는 것은 `claims.role` 뿐이다. */
const AUTH_STUB = 'tok';

function signInAs(role: Role | null) {
  useAuthStore.setState({
    token: AUTH_STUB,
    claims: { sub: '1', role, name: '홍길동', channel: 'INTERNAL', exp: 9999999999 },
  });
}

/** 배지가 색뿐 아니라 아이콘도 함께 쓰는지 — 색만으로 의미를 전달하지 않는다. [@design NFR-022] */
function hasIcon(el: HTMLElement): boolean {
  return el.querySelector('svg') !== null;
}

describe('CurrentRoleBadge — 역할 없음', () => {
  it('역할이_없으면_미배정으로_보이고_작업자로_채우지_않는다', () => {
    renderWithProviders(<CurrentRoleBadge role={null} />);

    const badge = screen.getByText('미배정');
    expect(badge).toBeInTheDocument();
    expect(screen.queryByText('작업자')).toBeNull();
    // 역할 없음은 정상 상태가 아니라 조치가 필요한 상태다 — 색만으로는 그 차이가 전달되지 않는다.
    expect(hasIcon(badge), '미배정에 경고 아이콘이 없다').toBe(true);
  });

  it('undefined_도_미배정이다', () => {
    // 스토어에 claims 자체가 없는 상태(`claims?.role`)가 그대로 흘러 들어온다.
    renderWithProviders(<CurrentRoleBadge role={undefined} />);
    expect(screen.getByText('미배정')).toBeInTheDocument();
    expect(screen.queryByText('작업자')).toBeNull();
  });
});

describe('CurrentRoleBadge — 역할 있음 (표시가 달라지지 않는다)', () => {
  it.each([
    [Role.ADMIN, '관리자'],
    [Role.REVIEWER, '검수자'],
    [Role.WORKER, '작업자'],
    [Role.PORTAL_USER, '포털'],
  ])('%s 는 %s 로 범주 구분색과 함께 보인다', (role, label) => {
    renderWithProviders(<CurrentRoleBadge role={role} />);

    const badge = screen.getByText(label);
    // 범주 구분색 축을 그대로 쓴다 — 배지 사양(semantic 토큰)으로 갈아타면 두 화면의 역할 색이
    // 통째로 바뀐다(이번 변경이 바꾸지 않기로 한 축이다).
    for (const cls of (ROLE_COLOR[role] ?? '').split(' ')) {
      expect(badge, `${role} 배지가 범주 구분색을 쓰지 않는다`).toHaveClass(cls);
    }
    // 역할이 있으면 경고 아이콘을 붙이지 않는다 — 미배정과 구분돼야 한다.
    expect(hasIcon(badge)).toBe(false);
    expect(screen.queryByText('미배정')).toBeNull();
  });

  it('모르는_역할은_비우지_않고_원문을_중립_색으로_노출한다', () => {
    // 「모르는 역할」과 「역할 없음」은 다른 사실이라 같은 표시로 합치지 않는다.
    renderWithProviders(<CurrentRoleBadge role={'SOMETHING_NEW' as Role} />);

    expect(screen.getByText('SOMETHING_NEW')).toBeInTheDocument();
    expect(screen.queryByText('미배정')).toBeNull();
  });
});

describe('상단 헤더(GNB)의 역할 배지', () => {
  it('역할이_없으면_미배정이_보인다', () => {
    signInAs(null);
    renderWithProviders(<Gnb />);

    expect(screen.getByText('미배정')).toBeInTheDocument();
    // 구 구현이 채우던 기본값 — 되살아나면 여기서 잡힌다.
    expect(screen.queryByText('작업자')).toBeNull();
  });

  it('역할이_있으면_그대로_보인다', () => {
    signInAs(Role.REVIEWER);
    renderWithProviders(<Gnb />);

    expect(screen.getByText('검수자')).toBeInTheDocument();
    expect(screen.queryByText('미배정')).toBeNull();
  });
});

describe('접근 거부 화면의 역할 배지', () => {
  it('역할이_없으면_미배정이_보인다', () => {
    // 접근이 거부된 자리에서 없는 역할을 있는 것처럼 보이면 왜 막혔는지를 오히려 흐린다.
    signInAs(null);
    renderWithProviders(<ForbiddenPage />);

    expect(screen.getByText('현재 역할:')).toBeInTheDocument();
    expect(screen.getByText('미배정')).toBeInTheDocument();
    expect(screen.queryByText('작업자')).toBeNull();
  });

  it('역할이_있으면_그대로_보인다', () => {
    signInAs(Role.WORKER);
    renderWithProviders(<ForbiddenPage />);

    expect(screen.getByText('작업자')).toBeInTheDocument();
    expect(screen.queryByText('미배정')).toBeNull();
  });

  it('로그인_정보가_아예_없어도_미배정이다', () => {
    // 공개 화면이라 인증 전에도 열린다 — 그때 작업자로 보이면 안 된다.
    useAuthStore.getState().clear();
    renderWithProviders(<ForbiddenPage />);

    expect(screen.getByText('미배정')).toBeInTheDocument();
    expect(screen.queryByText('작업자')).toBeNull();
  });
});
