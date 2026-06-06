import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { act, render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';

import { ChannelGuard, RoleGuard } from '@/router/guards';
import { useAuthStore } from '@/stores/useAuthStore';
import { Role } from '@/lib/api/types';

/**
 * R5-2 — 딥링크 직접 진입 시 hydration 지연 상태에서 리다이렉트 보류.
 *
 * 토큰 복원(hydrate) 완료 전(isHydrated=false)에는 claims=null 이라도
 * /ingress·/forbidden 으로 튕기지 않고 로딩 스피너를 노출해야 한다.
 * hydration 완료 후 claims 판정으로 정상 진입.
 */
function renderAugmentDeepLink() {
  return render(
    <MemoryRouter initialEntries={['/augment']}>
      <Routes>
        <Route
          path="/augment"
          element={
            <ChannelGuard channel="INTERNAL">
              <RoleGuard allow={[Role.REVIEWER]}>
                <div>AUGMENT_PAGE</div>
              </RoleGuard>
            </ChannelGuard>
          }
        />
        <Route path="/forbidden" element={<div>FORBIDDEN_PAGE</div>} />
        <Route path="/ingress" element={<div>INGRESS_PAGE</div>} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('딥링크 hydration 가드', () => {
  beforeEach(() => {
    // hydration 미완료 + claims 없음 상태로 초기화 (딥링크 직접 진입 시점)
    useAuthStore.setState({ token: null, claims: null, isHydrated: false });
  });

  afterEach(() => {
    useAuthStore.setState({ token: null, claims: null, isHydrated: false });
  });

  it('hydration_미완료_상태에서_딥링크_진입시_ingress로_튕기지_않음', () => {
    renderAugmentDeepLink();
    // then: 리다이렉트 페이지가 아니라 로딩(인증 확인 중) 표시
    expect(screen.queryByText('INGRESS_PAGE')).not.toBeInTheDocument();
    expect(screen.queryByText('FORBIDDEN_PAGE')).not.toBeInTheDocument();
    expect(screen.getByText('인증 확인 중')).toBeInTheDocument();
  });

  it('hydration_완료_후_claims_정상이면_딥링크_페이지_진입', () => {
    const { rerender } = renderAugmentDeepLink();
    // when: hydration 완료 + REVIEWER/INTERNAL claims 복원
    act(() => {
      useAuthStore.setState({
        token: 'tok',
        claims: { sub: 'u', role: 'REVIEWER', channel: 'INTERNAL', exp: 9999999999 },
        isHydrated: true,
      });
    });
    rerender(
      <MemoryRouter initialEntries={['/augment']}>
        <Routes>
          <Route
            path="/augment"
            element={
              <ChannelGuard channel="INTERNAL">
                <RoleGuard allow={[Role.REVIEWER]}>
                  <div>AUGMENT_PAGE</div>
                </RoleGuard>
              </ChannelGuard>
            }
          />
          <Route path="/forbidden" element={<div>FORBIDDEN_PAGE</div>} />
          <Route path="/ingress" element={<div>INGRESS_PAGE</div>} />
        </Routes>
      </MemoryRouter>,
    );
    expect(screen.getByText('AUGMENT_PAGE')).toBeInTheDocument();
  });
});
