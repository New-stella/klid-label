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
