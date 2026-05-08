import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';

import { useAuthStore } from '@/stores/useAuthStore';

import { SessionIngressPage } from '../SessionIngressPage';

// helper: base64url
function b64url(obj: Record<string, unknown>): string {
  const json = JSON.stringify(obj);
  const utf8 = unescape(encodeURIComponent(json));
  const b64 = btoa(utf8);
  return b64.replace(/=+$/, '').replace(/\+/g, '-').replace(/\//g, '_');
}
function buildJwt(headerObj: Record<string, unknown>, payloadObj: Record<string, unknown>): string {
  return `${b64url(headerObj)}.${b64url(payloadObj)}.signature`;
}

function renderWithRoutes(initialEntries: string[]) {
  return render(
    <MemoryRouter initialEntries={initialEntries}>
      <Routes>
        <Route path="/ingress" element={<SessionIngressPage />} />
        <Route path="/dashboard" element={<div>DASHBOARD_HOME</div>} />
        <Route path="/portal" element={<div>PORTAL_HOME</div>} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('SessionIngressPage', () => {
  let assignSpy: ReturnType<typeof vi.fn>;
  let originalLocation: Location;

  beforeEach(() => {
    useAuthStore.getState().clear();
    assignSpy = vi.fn();
    originalLocation = window.location;
    Object.defineProperty(window, 'location', {
      writable: true,
      value: { ...originalLocation, href: 'http://app.local/ingress', assign: assignSpy },
    });
    vi.stubEnv('VITE_TOKEN_INGRESS', 'url');
    vi.stubEnv('VITE_CONTROL_LOGIN_URL', 'http://control.local/login');
    vi.stubEnv('VITE_PORTAL_LOGIN_URL', 'http://portal.local/login');
  });

  afterEach(() => {
    Object.defineProperty(window, 'location', { writable: true, value: originalLocation });
    vi.unstubAllEnvs();
  });

  it('URL_token_파라미터_수령_후_INTERNAL_채널_dashboard_navigate', async () => {
    const tok = buildJwt(
      { alg: 'HS256', typ: 'JWT' },
      { sub: 'u1', role: 'REVIEWER', channel: 'INTERNAL', exp: 9999999999 },
    );
    renderWithRoutes([`/ingress?token=${tok}`]);

    await waitFor(() => {
      expect(screen.getByText('DASHBOARD_HOME')).toBeInTheDocument();
    });
    expect(useAuthStore.getState().token).toBe(tok);
    expect(useAuthStore.getState().claims?.role).toBe('REVIEWER');
  });

  it('PORTAL_채널_토큰은_portal_경로로_navigate', async () => {
    const tok = buildJwt(
      { alg: 'HS256', typ: 'JWT' },
      { sub: 'u2', role: 'PORTAL_USER', channel: 'PORTAL', exp: 9999999999 },
    );
    renderWithRoutes([`/ingress?token=${tok}`]);

    await waitFor(() => {
      expect(screen.getByText('PORTAL_HOME')).toBeInTheDocument();
    });
  });

  it('토큰_없으면_상위_시스템_redirect', async () => {
    renderWithRoutes(['/ingress']);

    await waitFor(() => {
      expect(assignSpy).toHaveBeenCalled();
    });
    const target = assignSpy.mock.calls[0][0] as string;
    expect(target.startsWith('http://control.local/login')).toBe(true);
  });

  it('만료된_exp_클레임은_redirect_처리', async () => {
    const expired = buildJwt(
      { alg: 'HS256', typ: 'JWT' },
      { sub: 'u3', role: 'REVIEWER', channel: 'INTERNAL', exp: 1 }, // long expired
    );
    renderWithRoutes([`/ingress?token=${expired}`]);

    await waitFor(() => {
      expect(assignSpy).toHaveBeenCalled();
    });
    expect(useAuthStore.getState().token).toBeNull();
  });
});
