import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import MockAdapter from 'axios-mock-adapter';

import { apiClient } from '@/lib/api/client';
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
        <Route path="/role-claim" element={<div>ROLE_CLAIM_HOME</div>} />
        <Route path="/dev/login" element={<div>DEV_LOGIN</div>} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('SessionIngressPage', () => {
  let assignSpy: ReturnType<typeof vi.fn>;
  let originalLocation: Location;
  let mock: MockAdapter;

  beforeEach(() => {
    useAuthStore.getState().clear();
    mock = new MockAdapter(apiClient);
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
    mock.restore();
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

  // ─────────────────────────────────────────────────────────────────
  // [@design SCREEN-002] [@design ADR-063] [@design UC-041]
  // 관제(INTERNAL) 진입 시 서버 인가 role 은 GET /v1/me 가 진실원이다.
  // ─────────────────────────────────────────────────────────────────

  // 수용기준 1 — 관제 진입자(role=null)는 대시보드가 아니라 권한안내/부트스트랩으로 간다.
  it('AC1_role_null_이면_role_claim_으로_라우팅된다', async () => {
    // 관제 토큰에는 role 클레임이 실리지 않는다(claims.role=null). /me 도 role=null 을 준다.
    const tok = buildJwt(
      { alg: 'HS256', typ: 'JWT' },
      { sub: 'u10', channel: 'INTERNAL', exp: 9999999999 },
    );
    mock.onGet('/me').reply(200, {
      success: true,
      data: { sub: 'u10', role: null, channel: 'INTERNAL' },
      message: null,
      errorCode: null,
    });
    renderWithRoutes([`/ingress?token=${tok}`]);

    await waitFor(() => {
      expect(screen.getByText('ROLE_CLAIM_HOME')).toBeInTheDocument();
    });
    // /me 서버 role(null)이 claims 에 주입돼 가드도 같은 판정을 본다.
    expect(useAuthStore.getState().claims?.role).toBeNull();
  });

  // 수용기준 4(회귀) — 서버 role 이 있으면 종전대로 대시보드로 진입한다.
  it('AC4_회귀_서버_role_이_있으면_dashboard_로_진입한다', async () => {
    const tok = buildJwt(
      { alg: 'HS256', typ: 'JWT' },
      { sub: 'u11', role: 'REVIEWER', channel: 'INTERNAL', exp: 9999999999 },
    );
    mock.onGet('/me').reply(200, {
      success: true,
      data: { sub: 'u11', role: 'REVIEWER', channel: 'INTERNAL' },
      message: null,
      errorCode: null,
    });
    renderWithRoutes([`/ingress?token=${tok}`]);

    await waitFor(() => {
      expect(screen.getByText('DASHBOARD_HOME')).toBeInTheDocument();
    });
  });

  // 판정의 진실원이 토큰 클레임이 아니라 /me 임을 못박는다 — 토큰에는 role 이 없어도(관제 토큰)
  // /me 가 role 을 주면 대시보드로 간다.
  it('토큰_클레임에_role_이_없어도_me_가_role_을_주면_dashboard', async () => {
    const tok = buildJwt(
      { alg: 'HS256', typ: 'JWT' },
      { sub: 'u12', channel: 'INTERNAL', exp: 9999999999 }, // role 클레임 없음
    );
    mock.onGet('/me').reply(200, {
      success: true,
      data: { sub: 'u12', role: 'WORKER', channel: 'INTERNAL' },
      message: null,
      errorCode: null,
    });
    renderWithRoutes([`/ingress?token=${tok}`]);

    await waitFor(() => {
      expect(screen.getByText('DASHBOARD_HOME')).toBeInTheDocument();
    });
    // ★핵심 회귀 방지 — 토큰엔 role 이 없지만 /me 서버 role 이 claims 에 주입돼야
    //   RoleGuard(claims.role 을 읽음)가 관제 재방문 role 보유자를 튕기지 않는다.
    expect(useAuthStore.getState().claims?.role).toBe('WORKER');
  });

  // /me 조회 실패 시에는 토큰 클레임 role 로 폴백한다(유효 세션이 막다른 길에 빠지지 않게).
  it('me_조회_실패시_토큰_role_로_폴백해_dashboard', async () => {
    const tok = buildJwt(
      { alg: 'HS256', typ: 'JWT' },
      { sub: 'u13', role: 'REVIEWER', channel: 'INTERNAL', exp: 9999999999 },
    );
    mock.onGet('/me').networkError();
    renderWithRoutes([`/ingress?token=${tok}`]);

    await waitFor(() => {
      expect(screen.getByText('DASHBOARD_HOME')).toBeInTheDocument();
    });
  });
});
