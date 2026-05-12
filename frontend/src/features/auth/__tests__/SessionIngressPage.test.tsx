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
        <Route path="/dev/login" element={<div>DEV_LOGIN</div>} />
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

  // DEV 빌드(Vitest 기본 import.meta.env.DEV=true) 에서는 토큰 없음/만료 시
  // upstream redirect 대신 /dev/login 으로 이동한다 (관제서버 미연결 환경 막다른 길 방지).
  it('DEV_빌드_토큰_없으면_dev_login_으로_이동', async () => {
    renderWithRoutes(['/ingress']);

    await waitFor(() => {
      expect(screen.getByText('DEV_LOGIN')).toBeInTheDocument();
    });
    expect(assignSpy).not.toHaveBeenCalled();
  });

  it('DEV_빌드_만료된_exp_클레임은_dev_login_으로_이동', async () => {
    const expired = buildJwt(
      { alg: 'HS256', typ: 'JWT' },
      { sub: 'u3', role: 'REVIEWER', channel: 'INTERNAL', exp: 1 }, // long expired
    );
    renderWithRoutes([`/ingress?token=${expired}`]);

    await waitFor(() => {
      expect(screen.getByText('DEV_LOGIN')).toBeInTheDocument();
    });
    expect(useAuthStore.getState().token).toBeNull();
    expect(assignSpy).not.toHaveBeenCalled();
  });
});
