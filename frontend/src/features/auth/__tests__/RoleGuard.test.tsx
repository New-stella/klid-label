import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';

import { RoleGuard } from '@/router/guards';
import { useAuthStore } from '@/stores/useAuthStore';

function renderRouter(initialPath: string, allow: ('REVIEWER' | 'WORKER' | 'PORTAL_USER')[]) {
  return render(
    <MemoryRouter initialEntries={[initialPath]}>
      <Routes>
        <Route
          path="/manage"
          element={
            <RoleGuard allow={allow}>
              <div>MANAGE_PAGE</div>
            </RoleGuard>
          }
        />
        <Route path="/forbidden" element={<div>FORBIDDEN_PAGE</div>} />
        <Route path="/ingress" element={<div>INGRESS_PAGE</div>} />
        <Route path="/role-claim" element={<div>ROLE_CLAIM_PAGE</div>} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('RoleGuard', () => {
  let assignSpy: ReturnType<typeof vi.fn>;
  let originalLocation: Location;

  beforeEach(() => {
    useAuthStore.getState().clear();
    assignSpy = vi.fn();
    originalLocation = window.location;
    Object.defineProperty(window, 'location', {
      writable: true,
      value: { ...originalLocation, href: 'http://app.local/manage', assign: assignSpy },
    });
    vi.stubEnv('VITE_CONTROL_LOGIN_URL', 'http://control.local/login');
    vi.stubEnv('VITE_PORTAL_LOGIN_URL', 'http://portal.local/login');
  });

  afterEach(() => {
    Object.defineProperty(window, 'location', { writable: true, value: originalLocation });
    vi.unstubAllEnvs();
  });

  it('REVIEWER는_manage_접근_허용', () => {
    useAuthStore.setState({
      token: 'tok',
      claims: { sub: 'u', role: 'REVIEWER', channel: 'INTERNAL', exp: 9999999999 },
    });
    renderRouter('/manage', ['REVIEWER']);
    expect(screen.getByText('MANAGE_PAGE')).toBeInTheDocument();
  });

  it('WORKER가_manage_접근_시_forbidden_navigate', () => {
    useAuthStore.setState({
      token: 'tok',
      claims: { sub: 'u', role: 'WORKER', channel: 'INTERNAL', exp: 9999999999 },
    });
    renderRouter('/manage', ['REVIEWER']);
    expect(screen.getByText('FORBIDDEN_PAGE')).toBeInTheDocument();
  });

  it('claims_없으면_ingress로_navigate', () => {
    renderRouter('/manage', ['REVIEWER']);
    expect(screen.getByText('INGRESS_PAGE')).toBeInTheDocument();
  });

  // ─────────────────────────────────────────────────────────────────
  // [@design SCREEN-002] [@design ADR-021] [@design ADR-063]
  // ★가드 진실원 = 서버 LS_USER_ROLE(/me). 관제 토큰에는 우리 role 이 실리지 않아 claims.role 이
  //   null 로 디코드된다 — SessionIngress 가 /me 서버 role 을 setServerRole 로 주입하면 가드가
  //   그 값을 보고 관제 재방문 role 보유자를 통과시킨다(구 결함: role 보유자가 /role-claim 으로 튕김).
  // ─────────────────────────────────────────────────────────────────
  it('관제형_토큰_role_클레임_없음에_서버_role_주입되면_가드_통과한다', () => {
    // 관제 토큰 디코드 결과: role=null (우리 role 클레임 미포함).
    useAuthStore.setState({
      token: 'control-tok',
      claims: { sub: 'u', role: null, channel: 'INTERNAL', exp: 9999999999 },
      isHydrated: true,
    });
    // SessionIngress 가 /me 서버 role 을 주입한 것과 동일.
    useAuthStore.getState().setServerRole('ADMIN');
    // ADMIN 은 계층으로 REVIEWER 자리를 만족한다 — 튕기지 않고 통과.
    renderRouter('/manage', ['REVIEWER']);
    expect(screen.getByText('MANAGE_PAGE')).toBeInTheDocument();
    expect(screen.queryByText('ROLE_CLAIM_PAGE')).toBeNull();
    expect(useAuthStore.getState().claims?.role).toBe('ADMIN');
  });

  it('서버_role_이_null_이면_role_claim_으로_보낸다', () => {
    useAuthStore.setState({
      token: 'control-tok',
      claims: { sub: 'u', role: null, channel: 'INTERNAL', exp: 9999999999 },
      isHydrated: true,
    });
    useAuthStore.getState().setServerRole(null); // 무권한 온보딩
    renderRouter('/manage', ['REVIEWER']);
    expect(screen.getByText('ROLE_CLAIM_PAGE')).toBeInTheDocument();
    expect(useAuthStore.getState().claims?.role).toBeNull();
  });

  it('만료된_exp는_상위_시스템_redirect', async () => {
    useAuthStore.setState({
      token: 'tok',
      claims: { sub: 'u', role: 'REVIEWER', channel: 'INTERNAL', exp: 1 },
    });
    renderRouter('/manage', ['REVIEWER']);
    await waitFor(() => {
      expect(assignSpy).toHaveBeenCalled();
    });
  });
});
