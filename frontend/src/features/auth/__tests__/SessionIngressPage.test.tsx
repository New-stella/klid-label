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

  // /me 조회 실패 + 토큰 클레임 role 이 <있으면> 종전대로 폴백한다.
  // ★이 폴백의 취지(유효 세션을 막다른 길에 빠뜨리지 않는다)는 2026-09-07 라운드에서도 그대로
  //   살아 있다 — 갈라진 것은 401 갈래와 「role 도 없는」 갈래뿐이다. 이 케이스가 그 회귀 가드다.
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

  // ─────────────────────────────────────────────────────────────────
  // ★조회 실패를 「역할 없음」으로 뭉개지 않는다 (2026-09-07 · @design SEQ-034 · SCREEN-002 v32)
  //
  //   구 동작 폐기 — catch 가 <모든> 실패를 토큰 role 폴백으로 처리해, role 이 없는 토큰이면
  //   인증 실패·서버 장애가 전부 /role-claim 으로 떨어졌다. 그 화면은 *"이 시스템에는 아직
  //   관리자가 없습니다"* 를 말하므로 **오류가 사양으로 위장**됐다(246 실측 2026-09-07 —
  //   9월 4일자 만료 토큰이 남아 /me 401 → 이 경로로 낙하).
  // ─────────────────────────────────────────────────────────────────

  it('★me_가_401_이면_role_claim_이_아니라_재로그인으로_보낸다', async () => {
    // 관제 토큰은 만료돼도 exp 검증을 통과할 수 있다(서버 시계·서명 검증은 BE 소유).
    // 여기서는 exp 는 살아 있는데 서버가 401 을 주는 상태 — 인증이 유효하지 않다는 뜻이다.
    const tok = buildJwt(
      { alg: 'HS256', typ: 'JWT' },
      { sub: 'u14', channel: 'INTERNAL', exp: 9999999999 },
    );
    mock.onGet('/me').reply(401, {
      success: false,
      data: null,
      message: '인증이 필요합니다.',
      errorCode: 'UNAUTHORIZED',
    });
    renderWithRoutes([`/ingress?token=${tok}`]);

    // DEV 빌드의 인증 실패 결말은 /dev/login 이다 — 기존 만료 처리와 <같은 결말>이어야 한다.
    await waitFor(() => {
      expect(screen.getByText('DEV_LOGIN')).toBeInTheDocument();
    });
    expect(screen.queryByText('ROLE_CLAIM_HOME')).toBeNull();
    expect(useAuthStore.getState().token).toBeNull();
  });

  it('★me_가_장애로_실패하고_토큰에도_role_이_없으면_role_claim_이_아니라_오류를_알린다', async () => {
    const tok = buildJwt(
      { alg: 'HS256', typ: 'JWT' },
      { sub: 'u15', channel: 'INTERNAL', exp: 9999999999 }, // role 클레임 없음
    );
    mock.onGet('/me').reply(500);
    renderWithRoutes([`/ingress?token=${tok}`]);

    await waitFor(() => {
      expect(screen.getByText('사용자 정보를 확인할 수 없습니다')).toBeInTheDocument();
    });
    expect(screen.queryByText('ROLE_CLAIM_HOME')).toBeNull();
    // 인증 실패로 오인시키지 않는다 — 사유가 다르면 사용자가 할 일도 다르다.
    expect(screen.queryByText('DEV_LOGIN')).toBeNull();
  });

  it('★역할이_정말_없으면_종전대로_role_claim_으로_간다', async () => {
    // 위 두 케이스의 <양성 대조군>. 이것이 없으면 「어떤 실패에도 role_claim 으로 안 간다」로
    // 만들어도 통과하고, 그러면 최초 관리자가 될 길이 사라진다.
    const tok = buildJwt(
      { alg: 'HS256', typ: 'JWT' },
      { sub: 'u16', channel: 'INTERNAL', exp: 9999999999 },
    );
    mock.onGet('/me').reply(200, {
      success: true,
      data: { sub: 'u16', role: null, channel: 'INTERNAL' },
      message: null,
      errorCode: null,
    });
    renderWithRoutes([`/ingress?token=${tok}`]);

    await waitFor(() => {
      expect(screen.getByText('ROLE_CLAIM_HOME')).toBeInTheDocument();
    });
  });

  // ─────────────────────────────────────────────────────────────────
  // ★서버가 아는 이름을 버리지 않는다 (2026-09-07 · @design SHELL-001 · UI-035 · AC-1098)
  //   헤더 이름의 진실원은 이 응답이고 토큰 클레임은 보조다.
  // ─────────────────────────────────────────────────────────────────

  it('★토큰에_이름이_없어도_me_가_주는_이름이_claims_에_주입된다', async () => {
    const tok = buildJwt(
      { alg: 'HS256', typ: 'JWT' },
      { sub: 'u17', channel: 'INTERNAL', exp: 9999999999 }, // name 클레임 없음
    );
    mock.onGet('/me').reply(200, {
      success: true,
      data: { sub: 'u17', role: 'ADMIN', channel: 'INTERNAL', name: '찬기차장' },
      message: null,
      errorCode: null,
    });
    renderWithRoutes([`/ingress?token=${tok}`]);

    await waitFor(() => {
      expect(screen.getByText('DASHBOARD_HOME')).toBeInTheDocument();
    });
    // ★이 값이 비면 GNB 가 대체 표기('사용자')에 고착된다 — 그것이 고친 결함이다.
    expect(useAuthStore.getState().claims?.name).toBe('찬기차장');
  });

  it('★서버가_이름을_모르면_토큰_이름을_지우지_않는다', async () => {
    // 진실원이 비었을 때 보조 조달원까지 버리면 고친 결함이 그대로 재발한다.
    const tok = buildJwt(
      { alg: 'HS256', typ: 'JWT' },
      { sub: 'u18', channel: 'INTERNAL', exp: 9999999999, name: '토큰이름' },
    );
    mock.onGet('/me').reply(200, {
      success: true,
      data: { sub: 'u18', role: 'WORKER', channel: 'INTERNAL' }, // name 없음
      message: null,
      errorCode: null,
    });
    renderWithRoutes([`/ingress?token=${tok}`]);

    await waitFor(() => {
      expect(screen.getByText('DASHBOARD_HOME')).toBeInTheDocument();
    });
    expect(useAuthStore.getState().claims?.name).toBe('토큰이름');
  });
});
